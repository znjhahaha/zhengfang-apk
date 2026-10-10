#!/usr/bin/env python3
"""Bounded, immutable APK delivery. This module never builds or signs an APK."""
import argparse
import base64
import hashlib
import html
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPO = 'znjhahaha/zhengfang-apk'
STATIC_LIMIT = 25 * 1024 * 1024
APK_LIMIT = 512 * 1024 * 1024
CHUNK_SIZE = 20 * 1024 * 1024
KEY_ID = 'app-update-2026-01'
HOSTS = {'stable': 'dl.hidisiwa.xyz', 'test': 'dl-test.hidisiwa.xyz'}
ALLOWED_HOSTS = set(HOSTS.values()) | {'github.com', 'raw.githubusercontent.com',
    'release-assets.githubusercontent.com', 'gh-proxy.com', 'ghproxy.net', 'gitee.com', 'raw.giteeusercontent.com'}

class DeliveryError(RuntimeError):
    pass

class Budget:
    def __init__(self, seconds=360, clock=time.monotonic):
        self.clock = clock
        self.deadline = clock() + seconds
        self.events = []

    def remaining(self, maximum):
        value = min(maximum, self.deadline - self.clock())
        if value <= 0:
            raise DeliveryError('Task deadline reached')
        return value

    def run(self, command, label, maximum=60, input=None, env=None, cwd=None):
        started = self.clock()
        try:
            result = subprocess.run(command, input=input, capture_output=True,
                timeout=self.remaining(maximum), env=env, cwd=cwd)
            code = result.returncode
        except subprocess.TimeoutExpired:
            self.events.append(dict(stage=label, seconds=round(self.clock()-started, 3), result='timeout'))
            raise DeliveryError(label + ': time limit reached') from None
        self.events.append(dict(stage=label, seconds=round(self.clock()-started, 3), exitCode=code))
        print(json.dumps(self.events[-1]), flush=True)
        if code:
            # CLI output can contain authentication details or signed temporary URLs.
            codes = re.findall(rb'(?:code[: ]+|\[code: )(\d{4,6})', result.stderr + result.stdout)
            suffix = (' (provider codes '+','.join(c.decode() for c in codes)+')') if codes else ''
            raise DeliveryError(f'{label}: command exited {code}'+suffix)
        return result.stdout


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def strict_json(data):
    if isinstance(data, bytes):
        data = data.decode('utf-8', errors='strict')
    if len(data.encode()) > 128 * 1024:
        raise DeliveryError('Manifest too large')
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise DeliveryError('Duplicate JSON key')
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=unique,
        parse_constant=lambda _: (_ for _ in ()).throw(DeliveryError('Invalid JSON number')))


def public_keys():
    return strict_json((ROOT/'app/src/main/assets/app-update-keys.json').read_bytes())


def valid_url(url):
    if not isinstance(url, str) or any(ord(c) <= 32 for c in url):
        return False
    try:
        p = urlsplit(url)
        return len(url) <= 4096 and p.scheme == 'https' and p.hostname in ALLOWED_HOSTS and not p.username and not p.password and not p.fragment and p.port in (None, 443)
    except ValueError:
        return False


def validate_payload(p, channel=None):
    if not isinstance(p, dict):
        raise DeliveryError('Manifest payload must be an object')
    if p.get('channel') not in HOSTS or channel and p['channel'] != channel:
        raise DeliveryError('Wrong release channel')
    if p.get('packageName') != 'com.tyust.course':
        raise DeliveryError('Wrong APK package')
    for field, minimum, maximum in [('revision', 1, 2**63-1), ('versionCode', 1, 2**31-1), ('minSdk', 24, 1000), ('size', 1, APK_LIMIT)]:
        if type(p.get(field)) is not int or not minimum <= p[field] <= maximum:
            raise DeliveryError('Invalid manifest '+field)
    for field, pattern in [('sha256', r'[a-f0-9]{64}'), ('sourceSha', r'[a-f0-9]{40}'), ('buildId', r'[0-9]{1,24}'),
                           ('versionName', r'\d+\.\d+\.\d+'), ('publishedAt', r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z')]:
        if not isinstance(p.get(field), str) or not re.fullmatch(pattern, p[field]):
            raise DeliveryError('Invalid manifest '+field)
    if type(p.get('forceUpdate')) is not bool or not isinstance(p.get('releaseNotes'), str) or len(p['releaseNotes']) > 20000:
        raise DeliveryError('Invalid update display fields')
    if not isinstance(p.get('mirrors'), list) or not 1 <= len(p['mirrors']) <= 8:
        raise DeliveryError('No verified download mirrors')
    ids = set()
    for m in p['mirrors']:
        if not isinstance(m, dict) or not re.fullmatch(r'[a-z0-9-]{1,32}', m.get('id', '')) or m['id'] in ids:
            raise DeliveryError('Invalid or duplicate mirror')
        ids.add(m['id'])
        if not isinstance(m.get('name'), str) or not 1 <= len(m['name']) <= 40 or not valid_url(m.get('url', '')):
            raise DeliveryError('Invalid mirror URL')
    return p


def sign_manifest(payload, key_file=None):
    validate_payload(payload)
    raw = json.dumps(payload, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    with tempfile.TemporaryDirectory() as d:
        directory = Path(d)
        if key_file is None:
            key = os.environ.get('APP_UPDATE_SIGNING_KEY', '')
            if not key:
                raise DeliveryError('APP_UPDATE_SIGNING_KEY is not configured')
            key_file = directory/'private.pem'
            key_file.write_text(key); key_file.chmod(0o600)
        result = subprocess.run(['openssl', 'dgst', '-sha256', '-sign', str(key_file)], input=raw, capture_output=True, timeout=10)
        if result.returncode:
            raise DeliveryError('Manifest signing failed')
    envelope = dict(schemaVersion=2, keyId=KEY_ID, payload=base64.b64encode(raw).decode(), signature=base64.b64encode(result.stdout).decode())
    verify_manifest(envelope, payload['channel'])
    return envelope


def verify_manifest(envelope, channel=None, keys=None):
    if isinstance(envelope, (str, bytes)):
        envelope = strict_json(envelope)
    if not isinstance(envelope, dict) or len(json.dumps(envelope, ensure_ascii=False).encode()) > 128 * 1024:
        raise DeliveryError('Invalid or oversized manifest envelope')
    if type(envelope.get('schemaVersion')) is not int or envelope['schemaVersion'] != 2:
        raise DeliveryError('Unknown manifest format')
    encoded = (keys or public_keys()).get(envelope.get('keyId'))
    if not encoded:
        raise DeliveryError('Unknown manifest key')
    try:
        payload = base64.b64decode(envelope['payload'], validate=True)
        signature = base64.b64decode(envelope['signature'], validate=True)
        der = base64.b64decode(encoded, validate=True)
    except (ValueError, KeyError):
        raise DeliveryError('Malformed manifest encoding') from None
    with tempfile.TemporaryDirectory() as d:
        directory = Path(d); (directory/'public.der').write_bytes(der); (directory/'signature').write_bytes(signature)
        # Existing trusted embedded key set contains only P-256 SPKI public keys.
        result = subprocess.run(['openssl', 'dgst', '-sha256', '-verify', str(directory/'public.der'), '-keyform', 'DER', '-signature', str(directory/'signature')], input=payload, capture_output=True, timeout=10)
        if result.returncode:
            raise DeliveryError('Manifest signature failed')
    return validate_payload(strict_json(payload), channel)


def ensure_forward(previous, incoming):
    if not previous:
        return
    if previous['channel'] != incoming['channel'] or incoming['revision'] < previous['revision'] or incoming['versionCode'] < previous['versionCode']:
        raise DeliveryError('Refusing metadata rollback')
    if incoming['revision'] == previous['revision'] and incoming != previous:
        raise DeliveryError('Manifest revision conflict')
    if incoming['versionCode'] == previous['versionCode'] and any(previous[k] != incoming[k] for k in ['size', 'sha256']):
        raise DeliveryError('Refusing same-version APK replacement')


def fetch(url, target, budget, expected=None, maximum=60, allow_missing=False, revalidate=False):
    if not valid_url(url):
        raise DeliveryError('Unapproved public download URL')
    cap = expected['size'] if expected else 128 * 1024
    command = ['curl', '--silent', '--show-error', '--location', '--max-redirs', '5', '--proto', '=https', '--proto-redir', '=https',
        '--connect-timeout', '10', '--max-time', str(max(1, int(budget.remaining(maximum)))), '--speed-time', '20', '--speed-limit', '1',
        '--max-filesize', str(cap), '--header', 'Accept-Encoding: identity', '--output', str(target), '--write-out', '%{http_code}', url]
    if revalidate:
        command[-1:-1] = ['--header', 'Cache-Control: no-cache']
    status = budget.run(command, 'public-download:'+urlsplit(url).hostname, maximum=maximum).decode().strip()
    if status == '404' and allow_missing:
        Path(target).unlink(missing_ok=True); return False
    if status != '200':
        raise DeliveryError('Public download returned HTTP '+status)
    if expected and (Path(target).stat().st_size != expected['size'] or digest(target) != expected['sha256']):
        raise DeliveryError('Public APK digest or size mismatch')
    return True


def android_tool(name):
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '/data/paseo/android-sdk'
    paths = sorted((Path(sdk)/'build-tools').glob('*/'+name), reverse=True)
    found = str(paths[0]) if paths else shutil.which(name)
    if not found:
        raise DeliveryError('Android tool missing: '+name)
    return found


def apk_signer_digests(output):
    # Build Tools <=36: "Signer #1"; 37: "V2 Signer:". A certificate may
    # be repeated for multiple verified schemes, but every signer must be official.
    pattern = r'^(?:Signer #\d+|V[1-4](?:\.\d+)? Signer(?: #\d+)?):? certificate SHA-256 digest: ([a-fA-F0-9]{64})$'
    return {match.lower() for line in output.splitlines() for match in re.findall(pattern, line.strip())}


def inspect_apk(apk, budget):
    certs = budget.run([android_tool('apksigner'), 'verify', '--print-certs', str(apk)], 'verify-apk-signature', 30).decode()
    actual = apk_signer_digests(certs)
    official = (ROOT/'distribution/official-apk-signer.sha256').read_text().strip()
    if actual != {official}:
        raise DeliveryError('APK signer does not match official certificate')
    badging = budget.run([android_tool('aapt'), 'dump', 'badging', str(apk)], 'inspect-apk', 30).decode()
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    sdk = re.search(r"sdkVersion:'(\d+)'", badging)
    if not package or not sdk or package[1] != 'com.tyust.course':
        raise DeliveryError('Invalid release APK')
    return dict(packageName=package[1], versionCode=int(package[2]), versionName=package[3], minSdk=int(sdk[1]),
        size=Path(apk).stat().st_size, sha256=digest(apk), signerSha256=official)


def inspect_apk_packaging(apk):
    if not 0 < Path(apk).stat().st_size <= APK_LIMIT:
        raise DeliveryError('APK exceeds the 512 MiB update limit; compress native libraries before signing')
    with zipfile.ZipFile(apk) as archive:
        libraries = [entry for entry in archive.infolist() if entry.filename.startswith('lib/') and entry.filename.endswith('.so')]
        if any(entry.compress_type != zipfile.ZIP_DEFLATED for entry in libraries):
            raise DeliveryError('Release APK contains uncompressed native libraries; enable jniLibs.useLegacyPackaging')
        abis = sorted({entry.filename.split('/')[1] for entry in libraries})
        if abis != ['arm64-v8a', 'armeabi-v7a']:
            raise DeliveryError('Release APK must contain exactly the arm64-v8a and armeabi-v7a native libraries')
        return dict(nativeLibraries=len(libraries), nativeBytes=sum(entry.file_size for entry in libraries),
            compressedNativeBytes=sum(entry.compress_size for entry in libraries), abis=abis)


def create_receipt(apk, tests, source_sha, build_id, output, budget):
    info = inspect_apk(apk, budget)
    info['packaging'] = inspect_apk_packaging(apk)
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    for xml in Path(tests).glob('TEST-*.xml'):
        root = ET.parse(xml).getroot()
        for name in counts:
            counts[name] += int(root.get(name, 0))
    if not counts['tests'] or counts['failures'] or counts['errors']:
        raise DeliveryError('Successful JVM reports required')
    info.update(sourceSha=source_sha, buildId=str(build_id), repository=REPO, tests=counts)
    Path(output).write_text(json.dumps(info, indent=2)+'\n')
    return info


def gh_api(path, budget, method='GET', data=None, missing=False):
    with tempfile.TemporaryDirectory() as d:
        command = ['gh', 'api', path, '--method', method]
        if data is not None:
            body = Path(d)/'body.json'; body.write_text(json.dumps(data)); command += ['--input', str(body)]
        try:
            raw = budget.run(command, 'github-api:'+method, 30)
        except DeliveryError:
            # A separate read distinguishes a genuinely absent ref from other API errors.
            if not missing:
                raise
            proc = subprocess.run(['gh', 'api', path, '--include'], capture_output=True, timeout=budget.remaining(15))
            if re.search(rb'HTTP/\S+ 404', proc.stdout):
                return None
            raise
        return json.loads(raw) if raw else None


def publish_updates(envelope, budget, extra=None):
    payload = verify_manifest(envelope)
    ref = gh_api(f'repos/{REPO}/git/ref/heads/updates', budget, missing=True)
    parent = ref['object']['sha'] if ref else None
    old = gh_api(f'repos/{REPO}/contents/{payload["channel"]}.json?ref=updates', budget, missing=True) if ref else None
    if old:
        ensure_forward(verify_manifest(base64.b64decode(old['content'])), payload)
    tree = [{'path': payload['channel']+'.json', 'mode': '100644', 'type': 'blob', 'content': json.dumps(envelope, separators=(',', ':'))}]
    for name, data in (extra or {}).items():
        tree.append(dict(path=name, mode='100644', type='blob', content=json.dumps(data, ensure_ascii=False)))
    body = dict(tree=tree)
    if parent:
        commit = gh_api(f'repos/{REPO}/git/commits/{parent}', budget); body['base_tree'] = commit['tree']['sha']
    tree_sha = gh_api(f'repos/{REPO}/git/trees', budget, 'POST', body)['sha']
    commit = gh_api(f'repos/{REPO}/git/commits', budget, 'POST', dict(message=f'update {payload["channel"]} {payload["versionName"]} [skip ci]', tree=tree_sha, parents=[parent] if parent else []))
    if parent:
        gh_api(f'repos/{REPO}/git/refs/heads/updates', budget, 'PATCH', dict(sha=commit['sha'], force=False))
    else:
        gh_api(f'repos/{REPO}/git/refs', budget, 'POST', dict(ref='refs/heads/updates', sha=commit['sha']))


def payload_for(receipt, channel, notes, mirrors, revision=None, force=False):
    return dict(channel=channel, revision=revision or int(time.time()*1000),
        **{k: receipt[k] for k in ['packageName', 'versionCode', 'versionName', 'minSdk', 'size', 'sha256', 'sourceSha', 'buildId']},
        releaseNotes=notes, forceUpdate=bool(force), mirrors=mirrors,
        publishedAt=time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()))


def cf_mirror(receipt, channel):
    path = f'/releases/{receipt["versionName"]}/{receipt["sha256"]}/app-release.apk'
    return dict(id='cf', name='自有镜像', url='https://'+HOSTS[channel]+path)


def apk_candidates(receipt):
    original = f'https://github.com/{REPO}/releases/download/v{receipt["versionName"]}/app-release.apk'
    return [dict(id='gh-proxy', name='备用线路一', url='https://gh-proxy.com/'+original),
        dict(id='ghproxy-net', name='备用线路二', url='https://ghproxy.net/'+original), dict(id='github', name='GitHub 原始下载', url=original)]


def render_index(payload):
    title = '教务助手测试包' if payload['channel'] == 'test' else '教务助手下载'
    links = ''.join('<p><a href="'+html.escape(m['url'], quote=True)+'">'+html.escape(m['name'])+'</a></p>' for m in payload['mirrors'])
    return ('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
        '<link rel="stylesheet" href="/style.css"><title>'+title+'</title><main><h1>'+title+'</h1><p>v'+html.escape(payload['versionName'])+
        ' · '+format(payload['size']/1048576, '.2f')+' MiB</p><pre>'+html.escape(payload['releaseNotes'])+'</pre>'+links+
        '<p>线路已在本次发布时下载校验；实际速度随网络变化。</p><details><summary>SHA-256</summary><code>'+payload['sha256']+'</code></details>'
        '<p><a href="https://github.com/'+REPO+'/releases">GitHub 发布记录</a> · <a href="https://plugins.hidisiwa.xyz">插件站</a></p></main></html>')


def release_announcement(payload):
    return dict(id='release_v'+payload['versionName'].replace('.', '_')+'_release',
        title='教务助手 '+payload['versionName']+' 更新', content=payload['releaseNotes'],
        audience='app', contentType='markdown', showOnce=True, type='info', created_at=payload['publishedAt'])


def merged_announcements(payload, budget):
    from publish_announcement import merge_announcements
    urls = ['https://raw.githubusercontent.com/'+REPO+'/updates/announcement.json',
        'https://gitee.com/znj12345/zhengfang/raw/main/announcement.json']
    histories = []
    with tempfile.TemporaryDirectory() as d:
        for url in urls:
            try:
                file = Path(d)/'history.json'
                fetch(url, file, budget, maximum=15)
                # Validate legacy shapes before combining independent source histories.
                histories.append(merge_announcements(strict_json(file.read_bytes()), release_announcement(payload)))
            except (DeliveryError, ValueError):
                continue
    if not histories:
        raise DeliveryError('Announcement history unavailable; refusing to erase it')
    merged = histories[0]
    ids = {entry['id'] for entry in merged['announcements']}
    for history in histories[1:]:
        for entry in history['announcements']:
            if entry['id'] not in ids:
                merged['announcements'].append(entry); ids.add(entry['id'])
    return merged


def cf_project_exists(channel, budget):
    account = os.environ.get('CLOUDFLARE_ACCOUNT_ID', '')
    token = os.environ.get('CLOUDFLARE_API_TOKEN', '')
    if not re.fullmatch(r'[a-f0-9]{32}', account) or not token:
        raise DeliveryError('Dedicated Cloudflare deployment credentials are required')
    name = 'academic-app-download'+('-test' if channel == 'test' else '')
    url = f'https://api.cloudflare.com/client/v4/accounts/{account}/workers/scripts/{name}/settings'
    with tempfile.TemporaryDirectory() as d:
        response = Path(d)/'response.json'
        config = 'url = '+json.dumps(url)+'\nheader = '+json.dumps('Authorization: Bearer '+token)+'\n'
        code = budget.run(['curl', '--config', '-', '--silent', '--show-error', '--connect-timeout', '10',
            '--max-time', '20', '--output', str(response), '--write-out', '%{http_code}'], 'cf-project-status', 20,
            input=config.encode()).decode().strip()
        body = json.loads(response.read_bytes())
        if code == '404' and any(e.get('code') == 10007 for e in body.get('errors', [])):
            return False
        if code == '200' and body.get('success') is True:
            return True
        codes = ','.join(str(e.get('code')) for e in body.get('errors', []))
        raise DeliveryError('Cannot establish Cloudflare project state (HTTP '+code+'; codes '+codes+')')


def migration_pin(channel):
    """Public signed receipt for the old-client migration APK; never a latest manifest."""
    if channel != 'test':
        return None
    envelope = strict_json((ROOT/'distribution/legacy-migration-test.json').read_bytes())
    p = verify_manifest(envelope, 'test')
    if p['versionCode'] != 98 or p['sha256'] != '501d8f8d0b6a8a738c4dc20f9e0cb05633e5d31c64ef8299138d39771e377c36':
        raise DeliveryError('Unexpected legacy migration pin')
    return envelope, p


def download_index(directory):
    directory = Path(directory)
    # Kept outside public assets; deploy embeds this immutable map in the Worker.
    return directory.parent/(directory.name+'.downloads.json')


def stage_apk(apk, receipt, channel, directory):
    directory = Path(directory)
    if type(receipt.get('size')) is not int or not 0 < receipt['size'] <= APK_LIMIT:
        raise DeliveryError('APK exceeds the supported 512 MiB limit')
    if not re.fullmatch(r'[a-f0-9]{64}', receipt.get('sha256', '')) or not re.fullmatch(r'\d+\.\d+\.\d+', receipt.get('versionName', '')):
        raise DeliveryError('Invalid APK storage identity')
    if Path(apk).stat().st_size != receipt['size']:
        raise DeliveryError('APK size changed before staging')
    path = urlsplit(cf_mirror(receipt, channel)['url']).path
    if receipt['size'] <= STATIC_LIMIT:
        target = directory/path.lstrip('/'); target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(apk, target)
        if digest(target) != receipt['sha256']:
            raise DeliveryError('APK digest changed before staging')
        return
    parts = []
    checksum = hashlib.sha256()
    with Path(apk).open('rb') as stream:
        while data := stream.read(CHUNK_SIZE):
            checksum.update(data)
            part_path = f'/_apk/{receipt["sha256"]}/{len(parts):03d}.bin'
            target = directory/part_path.lstrip('/'); target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            parts.append(dict(path=part_path, size=len(data)))
    if checksum.hexdigest() != receipt['sha256'] or sum(part['size'] for part in parts) != receipt['size']:
        raise DeliveryError('APK digest or size changed while staging')
    index = download_index(directory)
    files = strict_json(index.read_bytes()) if index.exists() else {}
    files[path] = dict(size=receipt['size'], sha256=receipt['sha256'], parts=parts)
    index.write_text(json.dumps(files, separators=(',', ':')))


def prepare_static(apk, receipt, channel, directory, budget):
    directory = Path(directory); directory.mkdir(parents=True, exist_ok=True)
    host = 'https://'+HOSTS[channel]
    previous = []
    history_file = directory/'history.json'
    exists = cf_project_exists(channel, budget)
    if exists and fetch(host+'/history.json', history_file, budget, allow_missing=True, maximum=20):
        history = strict_json(history_file.read_bytes())
        if not isinstance(history, dict) or not isinstance(history.get('releases'), list) or len(history['releases']) > 3:
            raise DeliveryError('Invalid static mirror history')
        for envelope in history['releases']:
            p = verify_manifest(envelope, channel)
            if p['sha256'] != receipt['sha256']:
                previous.append((envelope, p))
    # Preserve the currently advertised APK even after an interrupted history update.
    latest = directory/(channel+'.json')
    if exists and fetch(host+'/'+channel+'.json', latest, budget, allow_missing=True, maximum=20):
        old_envelope = strict_json(latest.read_bytes())
        old = verify_manifest(old_envelope, channel)
        if receipt['versionCode'] < old['versionCode'] or (receipt['versionCode'] == old['versionCode'] and receipt['sha256'] != old['sha256']):
            raise DeliveryError('Refusing older or conflicting static deployment')
        if old['sha256'] != receipt['sha256'] and not any(p['sha256'] == old['sha256'] for _,p in previous):
            previous.append((old_envelope, old))
        (directory/'index.html').write_text(render_index(old))
    else:
        (directory/'index.html').write_text('<!doctype html><meta charset="utf-8"><p>下载镜像准备中，请稍后访问。</p>')
    if exists and channel == 'stable':
        fetch(host+'/announcement.json', directory/'announcement.json', budget, allow_missing=True, maximum=20)
    previous = sorted(previous, key=lambda e: e[1]['revision'], reverse=True)[:1 if channel == 'test' else 2]
    # Gitee version.json still advertises test98. Retention of newer tests must
    # never delete that immutable migration URL or advance the legacy prompt.
    pin = migration_pin(channel)
    if pin and pin[1]['sha256'] != receipt['sha256'] and not any(p['sha256'] == pin[1]['sha256'] for _, p in previous):
        previous.append(pin)
    for envelope, p in previous:
        mirror = cf_mirror(p, channel); retained = directory.parent/(p['sha256']+'.apk')
        fetch(mirror['url'], retained, budget, expected=p)
        stage_apk(retained, p, channel, directory)
        retained.unlink()
    history_file.write_text(json.dumps(dict(releases=[e for e,p in previous])))
    stage_apk(apk, receipt, channel, directory)
    (directory/'style.css').write_text('body{font:16px system-ui;background:#f4f6f5;color:#20352d;margin:0}main{max-width:680px;margin:40px auto;padding:24px}a{color:#176c4d}pre,code{white-space:pre-wrap;overflow-wrap:anywhere}a{display:inline-block;padding:8px 0}')
    (directory/'_headers').write_text('/*\n  X-Content-Type-Options: nosniff\n  Referrer-Policy: no-referrer\n  Content-Security-Policy: default-src \'none\'; style-src \'self\'; frame-ancestors \'none\'\n/*.json\n  Cache-Control: public, max-age=60, must-revalidate\n/\n  Cache-Control: no-cache\n/releases/*\n  Cache-Control: public, max-age=31536000, immutable\n  Content-Type: application/vnd.android.package-archive\n  Content-Disposition: attachment; filename="app-release.apk"\n/_apk/*\n  Cache-Control: public, max-age=31536000, immutable, no-transform\n  Content-Type: application/octet-stream\n')
    return previous


def deploy(directory, channel, budget):
    directory = Path(directory)
    if any(p.stat().st_size > STATIC_LIMIT for p in directory.rglob('*') if p.is_file()):
        raise DeliveryError('Static asset exceeds free 25 MiB limit')
    # Uses the existing free static assets service; no R2, database, or paid binding.
    config = dict(name='academic-app-download'+('-test' if channel == 'test' else ''), compatibility_date='2026-09-25', workers_dev=False,
        routes=[dict(pattern=HOSTS[channel], custom_domain=True)],
        assets=dict(directory=str(directory.resolve()), not_found_handling='404-page'))
    with tempfile.TemporaryDirectory() as d:
        index = download_index(directory)
        if index.exists():
            files = strict_json(index.read_bytes())
            validate_download_index(files, directory)
            worker = Path(d)/'worker.mjs'
            module = (ROOT/'distribution/chunk-worker.mjs').as_posix()
            worker.write_text('import { createDownloadWorker } from '+json.dumps(module)+';\nexport default createDownloadWorker('+json.dumps(files)+');\n')
            config['main'] = str(worker)
            config['assets'].update(binding='ASSETS', run_worker_first=['/releases/*'])
        path = Path(d)/'wrangler.json'; path.write_text(json.dumps(config))
        budget.run(['npx', '--no-install', 'wrangler', 'deploy', '--config', str(path)], 'cf-static-deploy', maximum=120, cwd=ROOT/'distribution')


def validate_download_index(files, directory):
    if not isinstance(files, dict) or not 1 <= len(files) <= 4:
        raise DeliveryError('Invalid download index')
    for path, entry in files.items():
        match = re.fullmatch(r'/releases/\d+\.\d+\.\d+/([a-f0-9]{64})/app-release\.apk', path)
        if not match or not isinstance(entry, dict) or entry.get('sha256') != match[1]:
            raise DeliveryError('Invalid download identity')
        if type(entry.get('size')) is not int or not STATIC_LIMIT < entry['size'] <= APK_LIMIT:
            raise DeliveryError('Invalid chunked download size')
        parts = entry.get('parts')
        if not isinstance(parts, list) or not 1 <= len(parts) <= (APK_LIMIT + CHUNK_SIZE - 1)//CHUNK_SIZE:
            raise DeliveryError('Invalid download parts')
        total = 0
        for number, part in enumerate(parts):
            expected_path = f'/_apk/{match[1]}/{number:03d}.bin'
            if not isinstance(part, dict) or part.get('path') != expected_path or type(part.get('size')) is not int or not 0 < part['size'] <= CHUNK_SIZE:
                raise DeliveryError('Invalid download part')
            target = Path(directory)/expected_path.lstrip('/')
            if not target.is_file() or target.stat().st_size != part['size']:
                raise DeliveryError('Download part is missing or truncated')
            total += part['size']
        if total != entry['size']:
            raise DeliveryError('Download parts do not match APK size')


def verify_with_recovery(action, check, report, budget, settle_delays=()):
    """Never repeat a write before checking whether its response alone was lost."""
    for attempt in range(2):
        failure = None
        try:
            action()
        except DeliveryError as e:
            failure = e
        verification_error = None
        for read_attempt in range(len(settle_delays)+1):
            try:
                check()
                return
            except DeliveryError as error:
                verification_error = error
                if 'digest or size mismatch' in str(error):
                    raise
                if failure and any(word in str(failure) for word in ['10000', '10001', '10002', '10003', '10004', '10005', '10006', '9109', 'Authentication', 'permission', 'limit']):
                    raise failure
                pending = str(error).startswith('Published manifest differs;') or re.fullmatch(
                    r'Public download returned HTTP (404|408|425|429|500|502|503|504)', str(error))
                if not pending or read_attempt == len(settle_delays):
                    break
                delay = settle_delays[read_attempt]
                if budget.remaining(delay+1) < delay+1:
                    raise error
                report['stages'].append(dict(target='cf', status='waiting', seconds=delay, reason=str(error)))
                time.sleep(delay)
        if attempt:
            raise failure or verification_error
        report['stages'].append(dict(target='cf', status='retry', reason=str(verification_error)))
        budget.remaining(1)


def deploy_release(apk, receipt, channel, notes, output, budget, rounds=1, publish_branch=True, key_file=None, force=False, publish_announcements=True):
    if not 1 <= rounds <= 3:
        raise DeliveryError('Invalid verification round count')
    report = dict(channel=channel, apk=receipt['sha256'], stages=[], events=budget.events, result='incomplete')
    mirrors = []
    try:
        # Read newest metadata before touching any public mirror (including old repair jobs).
        if publish_branch:
            old = gh_api(f'repos/{REPO}/contents/{channel}.json?ref=updates', budget, missing=True)
            if old:
                previous_payload = verify_manifest(base64.b64decode(old['content']), channel)
                if receipt['versionCode'] < previous_payload['versionCode'] or (receipt['versionCode'] == previous_payload['versionCode'] and receipt['sha256'] != previous_payload['sha256']):
                    raise DeliveryError('Refusing older or conflicting release repair')
        with tempfile.TemporaryDirectory() as d:
            directory = Path(d)/'site'
            previous = []
            static_ok = False
            # Official source is required even when a mirror already has the bytes.
            candidates = apk_candidates(receipt) if channel == 'stable' else []
            if candidates:
                official = candidates[-1]
                fetch(official['url'], Path(d)/'official.apk', budget, expected=receipt)
                mirrors.append(official)
                report['stages'].append(dict(target='github', status='verified'))
            # Keep time for proxies, signing and small metadata if optional CF stalls.
            cf_budget = Budget(budget.remaining(180 if channel == 'stable' else 280), clock=budget.clock)
            cf_budget.events = budget.events
            try:
                previous = prepare_static(apk, receipt, channel, directory, cf_budget)
                for index in range(rounds):
                    verify_with_recovery(lambda: deploy(directory, channel, cf_budget),
                        lambda: fetch(cf_mirror(receipt, channel)['url'], Path(d)/'verify.apk', cf_budget, expected=receipt), report, cf_budget,
                        settle_delays=(3, 8, 15))
                    report['stages'].append(dict(target='cf', round=index+1, status='verified', storage='chunked' if receipt['size'] > STATIC_LIMIT else 'single'))
                mirrors.insert(0, cf_mirror(receipt, channel)); static_ok = True
            except DeliveryError as e:
                report['stages'].append(dict(target='cf', status='failed', reason=str(e)))
                if channel == 'test':
                    raise
            for candidate in candidates[:-1]:
                try:
                    fetch(candidate['url'], Path(d)/'candidate.apk', budget, expected=receipt, maximum=30)
                    mirrors.insert(len(mirrors)-1, candidate)
                    report['stages'].append(dict(target=candidate['id'], status='verified'))
                except DeliveryError as e:
                    report['stages'].append(dict(target=candidate['id'], status='failed', reason=str(e)))
            if channel == 'stable' and not any(m['id'] != 'github' for m in mirrors):
                raise DeliveryError('Domestic delivery not ready; previous metadata retained')
            payload = payload_for(receipt, channel, notes, mirrors, force=force)
            envelope = sign_manifest(payload, key_file)
            announcements = None
            if channel == 'stable' and publish_announcements:
                try:
                    announcements = merged_announcements(payload, budget)
                except DeliveryError as e:
                    report['stages'].append(dict(target='announcements', status='failed', reason=str(e)))
            if static_ok:
                latest = directory/(channel+'.json')
                if latest.exists():
                    ensure_forward(verify_manifest(latest.read_bytes(), channel), payload)
                latest.write_text(json.dumps(envelope, separators=(',', ':')))
                (directory/'history.json').write_text(json.dumps(dict(releases=[envelope]+[e for e,p in previous])))
                (directory/'index.html').write_text(render_index(payload))
                if announcements is not None:
                    (directory/'announcement.json').write_text(json.dumps(announcements, ensure_ascii=False))
                def verify_published():
                    # Staging read the previous latest URL, which an edge may still
                    # cache for 60 seconds after deployment. Verify this exact new
                    # revision without weakening signature or payload equality checks.
                    fetch('https://'+HOSTS[channel]+'/'+channel+'.json?revision='+str(payload['revision']),
                        Path(d)/'manifest.json', budget, revalidate=True)
                    if verify_manifest((Path(d)/'manifest.json').read_bytes(), channel) != payload:
                        raise DeliveryError('Published manifest differs; GitHub metadata not advanced')
                try:
                    verify_with_recovery(lambda: deploy(directory, channel, budget), verify_published, report, budget,
                        settle_delays=(5, 15, 30))
                except DeliveryError as e:
                    report['stages'].append(dict(target='cf-metadata', status='failed', reason=str(e)))
                    if channel == 'test':
                        raise
                    mirrors = [m for m in mirrors if m['id'] != 'cf']
                    if not any(m['id'] != 'github' for m in mirrors):
                        raise DeliveryError('Domestic delivery not ready; previous GitHub metadata retained')
                    payload = payload_for(receipt, channel, notes, mirrors, force=force)
                    envelope = sign_manifest(payload, key_file)
            if publish_branch:
                publish_updates(envelope, budget, {'announcement.json': announcements} if announcements is not None else None)
            report['manifest'] = envelope; report['result'] = 'verified'
            return envelope
    except Exception as e:
        report['result'] = 'failed'; report['reason'] = str(e) if isinstance(e, DeliveryError) else type(e).__name__
        raise
    finally:
        Path(output).parent.mkdir(parents=True, exist_ok=True)
        Path(output).write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='command', required=True)
    receipt = sub.add_parser('receipt'); receipt.add_argument('--apk', type=Path, required=True); receipt.add_argument('--tests', type=Path, required=True)
    receipt.add_argument('--source', required=True); receipt.add_argument('--build-id', required=True); receipt.add_argument('--output', type=Path, required=True)
    static = sub.add_parser('deploy'); static.add_argument('--apk', type=Path, required=True); static.add_argument('--receipt', type=Path, required=True)
    static.add_argument('--channel', choices=HOSTS, required=True); static.add_argument('--notes', type=Path, required=True); static.add_argument('--output', type=Path, required=True)
    static.add_argument('--rounds', type=int, default=1); static.add_argument('--key-file', type=Path); static.add_argument('--no-publish-branch', action='store_true'); static.add_argument('--force-update', action='store_true')
    args = parser.parse_args(); budget = Budget()
    try:
        if args.command == 'receipt':
            create_receipt(args.apk, args.tests, args.source, args.build_id, args.output, budget)
        else:
            r = strict_json(args.receipt.read_bytes()); actual = inspect_apk(args.apk, budget)
            if any(actual[k] != r[k] for k in actual):
                raise DeliveryError('Receipt does not match APK')
            deploy_release(args.apk, r, args.channel, args.notes.read_text(), args.output, budget, args.rounds, not args.no_publish_branch, args.key_file, args.force_update)
    except (DeliveryError, ValueError, KeyError) as e:
        print('Delivery failed: '+str(e))
        raise SystemExit(1)
    finally:
        event_file = Path(str(args.output)+'.timings.json'); event_file.parent.mkdir(parents=True, exist_ok=True)
        event_file.write_text(json.dumps(budget.events, indent=2)+'\n')

if __name__ == '__main__':
    main()
