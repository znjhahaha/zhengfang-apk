#!/usr/bin/env python3
"""Download verified official release artifacts and repair one target, without Gradle."""
import argparse
import json
import os
from pathlib import Path
import re
from release_distribution import (Budget, DeliveryError, REPO, gh_api, fetch, digest,
    ABI_FILES, inspect_apk, verify_artifact_set, verify_manifest, deploy_release, strict_json)


def existing_release(tag, directory, budget):
    if not re.fullmatch(r'v1\.0\.\d+', tag):
        raise DeliveryError('Invalid existing release tag')
    release = gh_api(f'repos/{REPO}/releases/tags/{tag}', budget)
    if release.get('draft') or release.get('prerelease'):
        raise DeliveryError('An existing official release is required')
    assets = {a['name']: a for a in release.get('assets', [])}
    asset = assets.get('app-release.apk', {})
    if not re.fullmatch(r'sha256:[a-f0-9]{64}', asset.get('digest', '')):
        raise DeliveryError('GitHub asset digest is required')
    directory = Path(directory); directory.mkdir(parents=True, exist_ok=True)
    apk = directory/'app-release.apk'
    fetch(asset['browser_download_url'], apk, budget,
          expected=dict(size=asset['size'], sha256=asset['digest'][7:]))
    receipt = inspect_apk(apk, budget)
    if 'v'+receipt['versionName'] != tag:
        raise DeliveryError('APK version differs from release tag')
    commit = gh_api(f'repos/{REPO}/commits/{tag}', budget)['sha']
    receipt.update(sourceSha=commit, buildId=str(release['id']))
    if 'receipt.json' in assets:
        fetch(assets['receipt.json']['browser_download_url'], directory/'receipt.json', budget)
        stored = strict_json((directory/'receipt.json').read_bytes())
        if any(stored.get(k) != receipt[k] for k in receipt if k != 'buildId'):
            raise DeliveryError('Archived receipt differs from release artifact')
        receipt = stored
    elif receipt['versionCode'] >= 113:
        raise DeliveryError('ARM split releases require their archived build receipt')
    if receipt['versionCode'] >= 113:
        if set(receipt.get('artifacts', {})) != set(ABI_FILES):
            raise DeliveryError('Archived receipt must contain both ARM artifacts')
        for abi, filename in ABI_FILES.items():
            split = assets.get(filename, {})
            expected = receipt['artifacts'][abi]
            if (split.get('digest') != 'sha256:'+expected.get('sha256', '') or
                    split.get('size') != expected.get('size')):
                raise DeliveryError('GitHub ARM asset differs from archived receipt: '+abi)
            fetch(split['browser_download_url'], directory/filename, budget, expected=expected)
        verify_artifact_set(apk, receipt, budget)
    notes = release.get('body', '').strip()
    if 'release-notes.txt' in assets:
        fetch(assets['release-notes.txt']['browser_download_url'], directory/'release-notes.txt', budget)
        notes = (directory/'release-notes.txt').read_text().strip()
    if not notes:
        raise DeliveryError('Release notes unavailable')
    return apk, receipt, notes


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--tag', required=True)
    parser.add_argument('--target', choices=['mirrors', 'metadata', 'gitee-attachment'], required=True)
    parser.add_argument('--output', type=Path, default=Path('repair/report.json'))
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    budget = Budget(300 if args.target == 'gitee-attachment' else 720)
    report = dict(target=args.target, tag=args.tag, result='incomplete')
    try:
        if args.target == 'metadata':
            from publish_update_metadata import MetadataClient, publish_metadata
            raw = args.output.parent/'stable.json'
            fetch(f'https://raw.githubusercontent.com/{REPO}/updates/stable.json', raw, budget)
            envelope = json.loads(raw.read_text()); payload = verify_manifest(envelope, 'stable')
            if 'v'+payload['versionName'] != args.tag:
                raise DeliveryError('Requested release is no longer latest; refusing old metadata repair')
            publish_metadata(MetadataClient(os.environ.get('GITEE_TOKEN', ''), budget), envelope, budget)
        else:
            apk, receipt, notes = existing_release(args.tag, args.output.parent, budget)
            report['sha256'] = digest(apk)
            if args.target == 'mirrors':
                deploy_release(apk, receipt, 'stable', notes, args.output, budget)
            else:
                from sync_gitee_release import Gitee, sync_release
                sync_release(Gitee(os.environ.get('GITEE_TOKEN', ''), budget), args.tag, apk, notes, attachments_only=True)
        report['result'] = 'verified'
    except Exception as e:
        report['result'] = 'failed'
        report['reason'] = str(e) if isinstance(e, (DeliveryError, RuntimeError)) else type(e).__name__
        raise SystemExit('Delivery repair failed: '+report['reason']) from None
    finally:
        if not args.output.exists():
            args.output.write_text(json.dumps(report, indent=2)+'\n')
        Path(str(args.output)+'.timings.json').write_text(json.dumps(budget.events, indent=2)+'\n')

if __name__ == '__main__':
    main()
