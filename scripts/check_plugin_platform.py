#!/usr/bin/env python3
"""Fail an official promotion before publishing if the online plugin platform lags."""
import argparse,hashlib,io,json,pathlib,re,tempfile,zipfile
from release_distribution import Budget,DeliveryError,ROOT,strict_json
ORIGIN='https://plugins.hidisiwa.xyz'
BOOTSTRAP_VERSION=1

def digest(data):return hashlib.sha256(data).hexdigest()

def check_archive(data, sdk_version, rule_version=None):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        entries=z.infolist()
        if len(entries)>3000 or sum(f.file_size for f in entries)>32*1024*1024:raise DeliveryError('Plugin toolchain archive too large')
        names=[f.filename for f in entries]
        if len(names)!=len(set(names)) or any(pathlib.PurePosixPath(n).is_absolute() or '..' in pathlib.PurePosixPath(n).parts or '\\' in n or ':' in n for n in names):
            raise DeliveryError('Invalid plugin toolchain paths')
        lock=strict_json(z.read('sdk/api-lock.json'))
        if lock['version']!=sdk_version or lock['apiVersion']!=3 or lock!=strict_json(z.read('host-api/api-lock.json')):
            raise DeliveryError('Plugin SDK lock mismatch')
        for file in ['sdk/package.json','host-api/package.json']:
            if strict_json(z.read(file))['version']!=sdk_version:raise DeliveryError('Plugin SDK package version mismatch')
        for name,expected in lock['sha256'].items():
            relative=name.removeprefix('assets/academic-plugin/')
            if digest(z.read('host-api/'+name))!=expected or digest(z.read('sdk/'+relative))!=expected:
                raise DeliveryError('Plugin contract bytes mismatch')
        if rule_version is not None:
            policy=z.read('cli/security-policy.mjs').decode()
            for key,expected in [('SECURITY_CONTRACT',sdk_version),('SECURITY_RULE_VERSION',rule_version)]:
                match=re.search(key+r"\s*=\s*['\"]([^'\"]+)['\"]",policy)
                if not match or match[1]!=expected:raise DeliveryError('Plugin audit policy mismatch')
        return lock

def check_descriptor(metadata, app_root=ROOT):
    if metadata.get('schemaVersion')!=1 or metadata.get('bootstrapVersion')!=BOOTSTRAP_VERSION:
        raise DeliveryError('Plugin audit bootstrap mismatch; synchronize website and plugin-audit workflow')
    if metadata.get('apiVersion')!=3 or metadata.get('sdkVersion')!=metadata.get('contractVersion'):
        raise DeliveryError('Online SDK and audit contract disagree')
    if not re.fullmatch(r'3\.\d+\.\d+',str(metadata.get('sdkVersion',''))):raise DeliveryError('Invalid online SDK version')
    runtime_files=['contract.schema.json','manifest.schema.json','host-sdk.js','host-capabilities.json']
    if tuple(map(int, metadata['sdkVersion'].split('.'))) >= (3,3,0):
        runtime_files += ['userscript-bootstrap.js','userscript-network-guard.js']
    if tuple(map(int, metadata['sdkVersion'].split('.'))) >= (3,4,0):
        runtime_files += ['gecko/manifest.json','gecko/background.js','gecko/api.js','gecko/environment.js']
    for name in runtime_files:
        expected=metadata.get('contractSha256',{}).get('assets/academic-plugin/'+name)
        if expected!=digest((pathlib.Path(app_root)/'app/src/main/assets/academic-plugin'/name).read_bytes()):
            raise DeliveryError('Online plugin SDK is not synchronized with App: '+name+'; complete spec/PLUGIN-PLATFORM-RELEASE.md')
    return metadata

def check_local(folder, output=None):
    """Preflight a locally built portal. Official promotion still calls check_online."""
    folder=pathlib.Path(folder)
    metadata=check_descriptor(strict_json((folder/'downloads/platform-release.json').read_bytes()))
    checks=strict_json((folder/'downloads/checksums.json').read_bytes())
    wiki=strict_json((folder/'wiki/manifest.json').read_bytes())
    if checks.get('sdkVersion')!=metadata['sdkVersion'] or wiki.get('sdkVersion')!=metadata['sdkVersion']:
        raise DeliveryError('Local Wiki or downloads are stale')
    for field,documented in [('version','appVersion'),('versionCode','appVersionCode'),('source','appSource'),('status','status')]:
        if wiki.get(documented)!=metadata['app'][field]:raise DeliveryError('Local Wiki and release descriptor disagree')
    for kind,name in [('starter','plugin-starter-v3.zip'),('sdk','plugin-sdk-v3.zip')]:
        data=(folder/'downloads'/name).read_bytes()
        if digest(data)!=metadata['downloads'][kind]['sha256'] or digest(data)!=checks[name]:raise DeliveryError('Local download digest mismatch')
        lock=check_archive(data,metadata['sdkVersion'],metadata['ruleVersion'] if kind=='starter' else None)
        if lock['sha256']!=metadata['contractSha256']:raise DeliveryError('Local contract descriptor mismatch')
    if output:pathlib.Path(output).write_text(json.dumps({'verification':'local-only','metadata':metadata},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Local SDK, Wiki, downloads, App assets and audit rules synchronized: '+metadata['sdkVersion']+'; online promotion NOT verified')
    return metadata

def check_online(output=None):
    budget=Budget(120)
    with tempfile.TemporaryDirectory() as tmp:
        def get(path,limit=128*1024):
            target=pathlib.Path(tmp)/'response'
            budget.run(['curl','--silent','--show-error','--fail','--connect-timeout','10','--max-time','25','--max-filesize',str(limit),
                '--output',str(target),ORIGIN+path],'plugin-platform-read',30)
            return target.read_bytes()
        metadata=check_descriptor(strict_json(get('/api/platform-release')))
        if strict_json(get('/downloads/platform-release.json'))!=metadata:raise DeliveryError('Worker and static plugin release differ')
        checks=strict_json(get('/downloads/checksums.json'))
        wiki=strict_json(get('/wiki/manifest.json'))
        if checks.get('sdkVersion')!=metadata['sdkVersion'] or wiki.get('sdkVersion')!=metadata['sdkVersion']:
            raise DeliveryError('Plugin website documentation or downloads are stale')
        for field,documented in [('version','appVersion'),('versionCode','appVersionCode'),('source','appSource'),('status','status')]:
            if wiki.get(documented)!=metadata['app'][field]:raise DeliveryError('Wiki and Worker describe different App releases')
        for kind,name in [('starter','plugin-starter-v3.zip'),('sdk','plugin-sdk-v3.zip')]:
            target=metadata['downloads'][kind]
            if target['url']!=ORIGIN+'/downloads/'+name or target['sha256']!=checks[name]:raise DeliveryError('Plugin download metadata mismatch')
            data=get('/downloads/'+name,12*1024*1024)
            if digest(data)!=target['sha256']:raise DeliveryError('Plugin download digest mismatch')
            lock=check_archive(data,metadata['sdkVersion'],metadata['ruleVersion'] if kind=='starter' else None)
            if lock['sha256']!=metadata['contractSha256']:raise DeliveryError('Online contract descriptor mismatch')
    if output:pathlib.Path(output).write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
    print('Plugin SDK, Wiki, downloads, App contract and audit policy synchronized: '+metadata['sdkVersion'])
    return metadata

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output');parser.add_argument('--local-platform',help='Local portal dist preflight only; does not verify official promotion');args=parser.parse_args()
    if args.local_platform:check_local(args.local_platform,args.output)
    else:check_online(args.output)
