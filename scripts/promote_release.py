#!/usr/bin/env python3
"""Promote a successful trusted test run; never rebuild or replace its APK."""
import argparse
import json
from pathlib import Path
import re
from release_distribution import Budget, DeliveryError, REPO, ROOT, gh_api, inspect_apk, verify_artifact_set, artifact_receipts


def verify_run(run, receipt):
    if run.get('event') != 'workflow_dispatch' or run.get('status') != 'completed' or run.get('conclusion') != 'success':
        raise DeliveryError('A successful completed manual test run is required')
    if run.get('repository', {}).get('full_name') != REPO or run.get('head_repository', {}).get('full_name') != REPO:
        raise DeliveryError('Build must come from this repository')
    if run.get('path') != '.github/workflows/release.yml' or run.get('head_sha') != receipt.get('deliverySourceSha', receipt.get('sourceSha')) or str(run.get('id')) != receipt.get('deliveryRunId', receipt.get('buildId')):
        raise DeliveryError('Build receipt does not match the trusted workflow run')
    tests = receipt.get('tests', {})
    if not tests.get('tests') or tests.get('failures') or tests.get('errors'):
        raise DeliveryError('Successful checks required')


def download_test_run(run_id, directory, budget):
    if not re.fullmatch(r'[0-9]{1,24}', str(run_id)):
        raise DeliveryError('Invalid workflow run ID')
    run = gh_api(f'repos/{REPO}/actions/runs/{run_id}', budget)
    directory = Path(directory); directory.mkdir(parents=True, exist_ok=True)
    budget.run(['gh', 'run', 'download', str(run_id), '--repo', REPO, '--name', 'release-apk', '--dir', str(directory)], 'download-tested-artifact', 180)
    receipt = json.loads((directory/'receipt.json').read_text())
    verify_run(run, receipt)
    actual = inspect_apk(directory/'app-release.apk', budget)
    if any(actual[key] != receipt.get(key) for key in actual):
        raise DeliveryError('Test artifact does not match receipt')
    if receipt.get('versionCode', 0) >= 113:
        verify_artifact_set(directory/'app-release.apk', receipt, budget)
    from reuse_test_artifact import validate_origin, APK_INPUTS
    if receipt.get('deliveryRunId'):
        origin = gh_api(f'repos/{REPO}/actions/runs/{receipt['buildId']}', budget)
        validate_origin(origin, gh_api(f'repos/{REPO}/actions/runs/{receipt['buildId']}/jobs?per_page=100', budget)['jobs'])
        if origin['head_sha'] != receipt['sourceSha']:
            raise DeliveryError('Reused build source mismatch')
    comparison = gh_api(f'repos/{REPO}/compare/{receipt["sourceSha"]}...main', budget)
    if comparison.get('status') not in ('identical', 'ahead') or any(f['filename']==p or f['filename'].startswith(p+'/') for f in comparison.get('files', []) for p in APK_INPUTS):
        raise DeliveryError('Main must contain unchanged tested APK inputs; delivery-only repairs may advance independently')
    return receipt


def prepare_promotion_notes(tag, directory, root=ROOT):
    """Optional reviewed stable wording; APK and its build receipt stay unchanged."""
    archived = Path(root)/'release-notes'/f'{tag}.md'
    if not archived.is_file():
        return
    text = archived.read_text()
    marker = '## stable notes\n'
    if marker not in text:
        return
    notes = text.split(marker, 1)[1].split('\n## ', 1)[0].strip()
    if not notes:
        raise DeliveryError('Empty archived stable release notes')
    (Path(directory)/'release-notes.txt').write_text(notes+'\n')


def promote(run_id, directory, budget):
    receipt = download_test_run(run_id, directory, budget)
    directory = Path(directory); tag = 'v'+receipt['versionName']
    if not re.fullmatch(r'v1\.0\.[0-9]+', tag):
        raise DeliveryError('Unsupported version tag')
    prepare_promotion_notes(tag, directory)
    ref = gh_api(f'repos/{REPO}/git/ref/tags/{tag}', budget, missing=True)
    if ref:
        obj = ref['object']
        while obj['type'] == 'tag':
            obj = gh_api(f'repos/{REPO}/git/tags/{obj["sha"]}', budget)['object']
        if obj['sha'] != receipt['sourceSha']:
            raise DeliveryError('Existing release tag points to different source')
    else:
        gh_api(f'repos/{REPO}/git/refs', budget, 'POST', dict(ref='refs/tags/'+tag, sha=receipt['sourceSha']))
    release = gh_api(f'repos/{REPO}/releases/tags/{tag}', budget, missing=True)
    if release is None:
        release = gh_api(f'repos/{REPO}/releases', budget, 'POST', dict(tag_name=tag, name='Release '+tag, draft=True,
            prerelease=False, body=(directory/'release-notes.txt').read_text()))
    def current_asset(filename):
        current = gh_api(f'repos/{REPO}/releases/{release["id"]}', budget)
        return next((a for a in current.get('assets', []) if a.get('name') == filename), None)
    for _, filename, artifact in artifact_receipts(receipt):
        asset = current_asset(filename)
        if asset is None:
            try:
                budget.run(['gh', 'release', 'upload', tag, str(directory/filename), '--repo', REPO], 'github-apk-upload', 120)
            except DeliveryError:
                if current_asset(filename) is None:
                    raise
            asset = current_asset(filename)
        if not asset or asset.get('digest') != 'sha256:'+artifact['sha256'] or asset.get('size') != artifact['size']:
            raise DeliveryError('Existing GitHub APK differs; it will not be overwritten')
    # Receipt and notes are immutable companions used by standalone repair tasks.
    companions = {a['name']:a for a in gh_api(f'repos/{REPO}/releases/{release["id"]}', budget).get('assets', [])}
    for name in ['receipt.json', 'release-notes.txt']:
        if name not in companions:
            try:
                budget.run(['gh', 'release', 'upload', tag, str(directory/name), '--repo', REPO], 'github-release-evidence', 30)
            except DeliveryError:
                pass
        from release_distribution import digest
        stored = next((a for a in gh_api(f'repos/{REPO}/releases/{release["id"]}', budget).get('assets', []) if a['name'] == name), None)
        if not stored or stored.get('digest') != 'sha256:'+digest(directory/name):
            raise DeliveryError('Release evidence missing or conflicting; will not overwrite')
    gh_api(f'repos/{REPO}/releases/{release["id"]}', budget, 'PATCH', dict(draft=False, prerelease=False))
    print('Promoted verified original APK:', receipt['sha256'])


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--run-id', required=True); parser.add_argument('--directory', type=Path, default=Path('promoted'))
    args = parser.parse_args(); promote(args.run_id, args.directory, Budget(600))

if __name__ == '__main__':
    main()
