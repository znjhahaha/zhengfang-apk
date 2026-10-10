import copy
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_distribution as d
import repair_delivery as repair


def receipt():
    common = dict(packageName='com.tyust.course', versionCode=113, versionName='1.0.113', minSdk=24,
                  sourceSha='b'*40, buildId='123', signerSha256='e'*64)
    return dict(common, size=9, sha256='a'*64, artifacts={
        abi: dict(common, size=5+i, sha256=marker*64)
        for i, (abi, marker) in enumerate([('arm64-v8a', 'c'), ('armeabi-v7a', 'd')])})


def payload(value=None):
    value = value or receipt()
    mirrors = {abi: [d.cf_mirror(a, 'test')] for abi, _, a in d.artifact_receipts(value)}
    return d.payload_for(value, 'test', 'Synthetic ARM release', mirrors['universal'], revision=100,
                         artifact_mirrors={abi: mirrors[abi] for abi in d.ABI_FILES})


class ArmManifestTests(unittest.TestCase):
    def test_nested_payload_retains_universal_compatibility_and_distinct_mirrors(self):
        p = d.validate_payload(payload(), 'test')
        self.assertEqual((p['size'], p['sha256']), (9, 'a'*64))
        self.assertEqual(set(p['artifacts']), set(d.ABI_FILES))
        self.assertEqual(len({p['mirrors'][0]['url'], *(a['mirrors'][0]['url'] for a in p['artifacts'].values())}), 3)
        html = d.render_index(p)
        for label in ['ARM64', 'ARM32', 'ARM 通用兼容包']:
            self.assertIn(label, html)

    def test_invalid_nested_contract_and_incomplete_mirror_set_are_rejected(self):
        for key, value in [('size', 0), ('size', True), ('sha256', 'bad'), ('mirrors', [])]:
            p = payload(); p['artifacts']['arm64-v8a'][key] = value
            with self.subTest(key=key), self.assertRaises(d.DeliveryError): d.validate_payload(p)
        for value in [{}, {'x86_64': payload()['artifacts']['arm64-v8a']}, []]:
            p = payload(); p['artifacts'] = value
            with self.assertRaises(d.DeliveryError): d.validate_payload(p)
        with self.assertRaises(d.DeliveryError): d.payload_for(receipt(), 'test', '', [], artifact_mirrors={})

    def test_same_version_artifact_replacement_or_removal_is_not_a_mirror_refresh(self):
        original = payload()
        for replace in ['change', 'remove']:
            changed = copy.deepcopy(original); changed['revision'] += 1
            if replace == 'change': changed['artifacts']['armeabi-v7a']['sha256'] = 'f'*64
            else: del changed['artifacts']['armeabi-v7a']
            with self.assertRaises(d.DeliveryError): d.ensure_forward(original, changed)
        refresh = copy.deepcopy(original); refresh['revision'] += 1
        refresh['artifacts']['arm64-v8a']['mirrors'][0]['url'] = 'https://dl-test.hidisiwa.xyz/refreshed.apk'
        d.ensure_forward(original, refresh)

    def test_release_paths_are_fixed_even_if_receipt_contains_a_filename(self):
        value = receipt(); value['artifacts']['arm64-v8a']['fileName'] = '../../private.pem'
        artifacts = list(d.artifact_receipts(value))
        self.assertEqual(artifacts[1][1], 'app-arm64-v8a-release.apk')
        self.assertTrue(d.apk_candidates(artifacts[1][2])[-1]['url'].endswith('/app-arm64-v8a-release.apk'))


class ArmReceiptTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.common = dict(packageName='com.tyust.course', versionCode=113, versionName='1.0.113', minSdk=24, signerSha256='e'*64)
        for abi, filename in [('universal', 'app-release.apk'), *d.ABI_FILES.items()]:
            with zipfile.ZipFile(self.root/filename, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                for architecture in (d.ABI_FILES if abi == 'universal' else [abi]):
                    archive.writestr(f'lib/{architecture}/synthetic.so', b'ELF'*100)
        (self.root/'TEST-synthetic.xml').write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"/>')

    def inspect(self, file, _budget):
        file = Path(file)
        return dict(self.common, size=file.stat().st_size, sha256=d.digest(file))

    def create(self):
        return d.create_receipt(self.root/'app-release.apk', self.root, 'b'*40, '123', self.root/'receipt.json', d.Budget())

    def test_all_packages_have_independently_recorded_compression_architecture_and_hash(self):
        with patch.object(d, 'inspect_apk', side_effect=self.inspect):
            value = self.create(); d.verify_artifact_set(self.root/'app-release.apk', value, d.Budget())
        for abi, a in value['artifacts'].items():
            self.assertEqual(a['packaging']['abis'], [abi])
            self.assertLess(a['packaging']['compressedNativeBytes'], a['packaging']['nativeBytes'])
        self.assertEqual(value['tests']['tests'], 1)

    def test_mixed_signer_or_version_and_missing_split_stop_receipt(self):
        for field, wrong in [('signerSha256', 'f'*64), ('versionCode', 114)]:
            def inspect(file, budget):
                value = self.inspect(file, budget)
                if Path(file).name == d.ABI_FILES['arm64-v8a']: value[field] = wrong
                return value
            with patch.object(d, 'inspect_apk', side_effect=inspect), self.assertRaises(d.DeliveryError): self.create()
        (self.root/d.ABI_FILES['arm64-v8a']).unlink()
        with patch.object(d, 'inspect_apk', side_effect=self.inspect), self.assertRaises(FileNotFoundError): self.create()

    def test_wrong_abi_and_changed_bytes_cannot_reuse_receipt(self):
        with self.assertRaises(d.DeliveryError): d.inspect_apk_packaging(self.root/d.ABI_FILES['arm64-v8a'], 'armeabi-v7a')
        with patch.object(d, 'inspect_apk', side_effect=self.inspect):
            value = self.create()
            with (self.root/d.ABI_FILES['arm64-v8a']).open('ab') as file: file.write(b'changed')
            with self.assertRaises(d.DeliveryError): d.verify_artifact_set(self.root/'app-release.apk', value, d.Budget())
        del value['artifacts']['arm64-v8a']
        with self.assertRaises(d.DeliveryError): d.verify_artifact_set(self.root/'app-release.apk', value, d.Budget())

    def test_static_deployment_stages_every_hash_separately(self):
        with patch.object(d, 'inspect_apk', side_effect=self.inspect): value = self.create()
        with patch.object(d, 'cf_project_exists', return_value=False), patch.object(d, 'migration_pin', return_value=None):
            site = self.root/'site'; d.prepare_static(self.root/'app-release.apk', value, 'test', site, d.Budget())
        for _, _, artifact in d.artifact_receipts(value):
            staged = site/'releases'/value['versionName']/artifact['sha256']/'app-release.apk'
            self.assertTrue(staged.is_file()); self.assertEqual(d.digest(staged), artifact['sha256'])

    def archive(self):
        with patch.object(d, 'inspect_apk', side_effect=self.inspect): value = self.create()
        names = ['app-release.apk', *d.ABI_FILES.values(), 'receipt.json']
        assets = [dict(name=name, size=(self.root/name).stat().st_size,
                       digest='sha256:'+d.digest(self.root/name), browser_download_url=name) for name in names]
        return value, dict(id=7, draft=False, prerelease=False, body='Synthetic stable notes', assets=assets)

    def repair(self, release):
        def api(path, _budget):
            return {'sha': 'b'*40} if '/commits/' in path else release
        def fetch(url, target, _budget, expected=None):
            data = (self.root/url).read_bytes()
            target.write_bytes(data)
            if expected:
                self.assertEqual(len(data), expected['size'])
                self.assertEqual(d.digest(target), expected['sha256'])
        with patch.object(repair, 'gh_api', side_effect=api), patch.object(repair, 'fetch', side_effect=fetch), \
             patch.object(repair, 'inspect_apk', side_effect=self.inspect), patch.object(d, 'inspect_apk', side_effect=self.inspect):
            return repair.existing_release('v1.0.113', self.root/'repaired', d.Budget())

    def test_official_release_repair_downloads_and_verifies_the_complete_original_set(self):
        original, release = self.archive()
        apk, restored, _notes = self.repair(release)
        self.assertEqual(original, restored)
        for abi, filename, expected in d.artifact_receipts(restored):
            self.assertEqual(d.digest(apk.parent/filename), expected['sha256'])

    def test_official_split_repair_refuses_missing_receipt_or_sibling_and_conflicting_digest(self):
        _value, release = self.archive()
        for missing in ['receipt.json', *d.ABI_FILES.values()]:
            changed = copy.deepcopy(release)
            changed['assets'] = [a for a in changed['assets'] if a['name'] != missing]
            with self.subTest(missing=missing), self.assertRaises(d.DeliveryError): self.repair(changed)
        changed = copy.deepcopy(release)
        changed['assets'][1]['digest'] = 'sha256:'+'f'*64
        with self.assertRaises(d.DeliveryError): self.repair(changed)

    def test_official_split_repair_checks_nested_signers_not_just_universal_receipt(self):
        value, release = self.archive()
        value['artifacts']['arm64-v8a']['signerSha256'] = 'f'*64
        (self.root/'receipt.json').write_text(json.dumps(value))
        with self.assertRaises(d.DeliveryError): self.repair(release)


class ArmPublicationTests(unittest.TestCase):
    def test_failed_split_download_cannot_sign_or_publish_manifest(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            def prepare(_apk, _receipt, _channel, site, _budget): site.mkdir(parents=True); return []
            def fetch(_url, _target, _budget, expected=None, **_):
                if expected and expected['sha256'] == 'd'*64: raise d.DeliveryError('Public APK digest or size mismatch')
            with patch.object(d, 'verify_artifact_set'), patch.object(d, 'gh_api', return_value=None), \
                 patch.object(d, 'prepare_static', side_effect=prepare), patch.object(d, 'deploy'), \
                 patch.object(d, 'fetch', side_effect=fetch), patch.object(d, 'sign_manifest') as sign, \
                 patch.object(d, 'publish_updates') as publish:
                with self.assertRaises(d.DeliveryError):
                    d.deploy_artifact_set(root/'app-release.apk', receipt(), 'test', 'notes', root/'report.json', d.Budget())
                sign.assert_not_called(); publish.assert_not_called()
            self.assertEqual(json.loads((root/'report.json').read_text('utf-8'))['result'], 'failed')

    def test_manifest_is_published_only_after_every_file_and_public_metadata_verify(self):
        events = []; signed = {}
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            def prepare(_apk, _receipt, _channel, site, _budget): site.mkdir(parents=True); return []
            def sign(p, _key): signed.update(p); events.append('sign'); return {'synthetic': True}
            def fetch(_url, target, _budget, expected=None, **_):
                if expected: events.append('file:'+expected['sha256'][0])
                else: events.append('metadata'); target.write_text('{}')
            with patch.object(d, 'verify_artifact_set'), patch.object(d, 'gh_api', return_value=None), \
                 patch.object(d, 'prepare_static', side_effect=prepare), patch.object(d, 'deploy'), \
                 patch.object(d, 'fetch', side_effect=fetch), patch.object(d, 'sign_manifest', side_effect=sign), \
                 patch.object(d, 'verify_manifest', side_effect=lambda *_: signed), \
                 patch.object(d, 'publish_updates', side_effect=lambda *_: events.append('publish')):
                d.deploy_artifact_set(root/'app-release.apk', receipt(), 'test', 'notes', root/'report.json', d.Budget())
            self.assertEqual(events, ['file:a', 'file:c', 'file:d', 'sign', 'metadata', 'publish'])


if __name__ == '__main__': unittest.main()
