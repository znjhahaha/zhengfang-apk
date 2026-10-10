import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_distribution as d


class ApkStorageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.site = self.root/'site'
        self.source = self.root/'input.apk'
        for name, value in [('STATIC_LIMIT', 8), ('CHUNK_SIZE', 4), ('APK_LIMIT', 128)]:
            scoped = patch.object(d, name, value)
            scoped.start()
            self.addCleanup(scoped.stop)

    def stage(self, data=b'abcdefghijklmnopq'):
        self.source.write_bytes(data)
        receipt = dict(versionName='1.0.112', size=len(data), sha256=hashlib.sha256(data).hexdigest())
        d.stage_apk(self.source, receipt, 'test', self.site)
        return receipt

    def test_small_apk_keeps_its_original_public_path(self):
        receipt = self.stage(b'12345678')
        target = self.site/'releases/1.0.112'/receipt['sha256']/'app-release.apk'
        self.assertEqual(target.read_bytes(), self.source.read_bytes())
        self.assertFalse(d.download_index(self.site).exists())

    def test_large_apk_parts_reconstruct_the_identical_signed_bytes(self):
        receipt = self.stage()
        files = json.loads(d.download_index(self.site).read_text())
        d.validate_download_index(files, self.site)
        entry = next(iter(files.values()))
        rebuilt = b''.join((self.site/part['path'].lstrip('/')).read_bytes() for part in entry['parts'])
        self.assertEqual(rebuilt, self.source.read_bytes())
        self.assertEqual(hashlib.sha256(rebuilt).hexdigest(), receipt['sha256'])
        self.assertTrue(all(path.stat().st_size <= d.STATIC_LIMIT for path in self.site.rglob('*') if path.is_file()))
        self.assertNotIn(d.download_index(self.site), list(self.site.rglob('*')))

    def test_retained_large_apk_and_new_apk_share_an_index(self):
        old = self.stage(b'old signed package')
        new = self.stage(b'new signed package')
        files = json.loads(d.download_index(self.site).read_text())
        self.assertEqual({entry['sha256'] for entry in files.values()}, {old['sha256'], new['sha256']})
        d.validate_download_index(files, self.site)

    def test_complete_arm_sets_fit_the_channel_retention_policy(self):
        packages = {}

        def release(version, split=True):
            def artifact(abi):
                data = f'original signed {version} {abi}'.encode()
                value = dict(versionName=f'1.0.{version}', versionCode=version,
                             revision=version, size=len(data), sha256=hashlib.sha256(data).hexdigest())
                packages[value['sha256']] = data
                return value
            value = artifact('universal')
            if split:
                value['artifacts'] = {abi: artifact(abi) for abi in d.ABI_FILES}
            return value

        current = release(114)
        history = [release(113), release(112)]
        legacy = release(98, split=False)
        for _, filename, artifact in d.artifact_receipts(current):
            (self.root/filename).write_bytes(packages[artifact['sha256']])

        for channel, count in [('test', 7), ('stable', 9)]:
            def fetch(url, target, _budget, expected=None, **kwargs):
                if url.endswith('/history.json'):
                    target.write_text(json.dumps(dict(releases=history)))
                elif url.endswith(f'/{channel}.json'):
                    target.write_text(json.dumps(history[0]))
                elif expected:
                    target.write_bytes(packages[expected['sha256']])
                else:
                    return False
                return True

            site = self.root/channel
            pin = (legacy, legacy) if channel == 'test' else None
            with self.subTest(channel=channel), \
                 patch.object(d, 'cf_project_exists', return_value=True), \
                 patch.object(d, 'fetch', side_effect=fetch), \
                 patch.object(d, 'verify_manifest', side_effect=lambda value, *_: value), \
                 patch.object(d, 'render_index', return_value='<p>Current release</p>'), \
                 patch.object(d, 'migration_pin', return_value=pin):
                d.prepare_static(self.root/'app-release.apk', current, channel, site, d.Budget())
                files = json.loads(d.download_index(site).read_text())
                self.assertEqual(len(files), count)
                d.validate_download_index(files, site)
                for entry in files.values():
                    rebuilt = b''.join((site/part['path'].lstrip('/')).read_bytes() for part in entry['parts'])
                    self.assertEqual(rebuilt, packages[entry['sha256']])

    def test_download_index_stays_bounded_after_arm_retention_support(self):
        for number in range(10):
            self.stage(f'original signed package {number}'.encode())
        files = json.loads(d.download_index(self.site).read_text())
        with self.assertRaisesRegex(d.DeliveryError, 'Invalid download index'):
            d.validate_download_index(files, self.site)

    def test_bad_original_digest_never_produces_a_download_index(self):
        self.source.write_bytes(b'wrong signed bytes')
        with self.assertRaisesRegex(d.DeliveryError, 'digest'):
            d.stage_apk(self.source, dict(versionName='1.0.112', size=self.source.stat().st_size, sha256='a'*64), 'test', self.site)
        self.assertFalse(d.download_index(self.site).exists())

    def test_oversized_apk_is_rejected_before_public_staging(self):
        self.source.write_bytes(b'x'*129)
        with self.assertRaisesRegex(d.DeliveryError, '512 MiB'):
            d.stage_apk(self.source, dict(size=129), 'test', self.site)
        self.assertFalse(self.site.exists())

    def test_truncated_part_prevents_deployment(self):
        self.stage()
        files = json.loads(d.download_index(self.site).read_text())
        part = next(iter(files.values()))['parts'][0]
        (self.site/part['path'].lstrip('/')).write_bytes(b'x')
        budget = Mock()
        with self.assertRaisesRegex(d.DeliveryError, 'truncated'):
            d.deploy(self.site, 'test', budget)
        budget.run.assert_not_called()

    def test_index_cannot_route_to_arbitrary_files_or_mismatched_identities(self):
        self.stage()
        original = json.loads(d.download_index(self.site).read_text())
        for change in ['path', 'size', 'digest']:
            files = copy.deepcopy(original)
            entry = next(iter(files.values()))
            if change == 'path': entry['parts'][0]['path'] = '/../private.pem'
            if change == 'size': entry['size'] += 1
            if change == 'digest': entry['sha256'] = 'f'*64
            with self.subTest(change=change), self.assertRaises(d.DeliveryError):
                d.validate_download_index(files, self.site)

    def test_large_download_uses_only_existing_worker_and_asset_bindings(self):
        self.stage()
        captured = {}
        def run(command, *args, **kwargs):
            config = json.loads(Path(command[command.index('--config')+1]).read_text())
            captured.update(config)
            self.assertIn('createDownloadWorker', Path(config['main']).read_text())
        d.deploy(self.site, 'test', Mock(run=run))
        self.assertEqual(captured['assets']['binding'], 'ASSETS')
        self.assertEqual(captured['assets']['run_worker_first'], ['/releases/*'])
        self.assertEqual(captured['name'], 'academic-app-download-test')
        self.assertFalse({'r2_buckets', 'd1_databases', 'kv_namespaces', 'services'} & captured.keys())


class ApkPackagingTests(unittest.TestCase):
    def test_release_receipt_records_native_compression_and_abi_coverage(self):
        with tempfile.TemporaryDirectory() as root:
            apk = Path(root)/'test.apk'
            with zipfile.ZipFile(apk, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr('lib/arm64-v8a/libtest.so', b'ELF'*100)
                archive.writestr('lib/armeabi-v7a/libtest.so', b'ELF'*100)
            info = d.inspect_apk_packaging(apk)
            self.assertEqual(info['abis'], ['arm64-v8a', 'armeabi-v7a'])
            self.assertEqual(info['nativeLibraries'], 2)
            self.assertLess(info['compressedNativeBytes'], info['nativeBytes'])

    def test_uncompressed_native_library_fails_before_upload(self):
        with tempfile.TemporaryDirectory() as root:
            apk = Path(root)/'test.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('lib/arm64-v8a/libtest.so', b'ELF')
            with self.assertRaisesRegex(d.DeliveryError, 'uncompressed native'):
                d.inspect_apk_packaging(apk)

    def test_missing_arm_architecture_or_added_x86_fails_before_upload(self):
        for abis in [['arm64-v8a'], ['arm64-v8a', 'armeabi-v7a', 'x86_64']]:
            with tempfile.TemporaryDirectory() as root, self.subTest(abis=abis):
                apk = Path(root)/'test.apk'
                with zipfile.ZipFile(apk, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                    for abi in abis: archive.writestr(f'lib/{abi}/libtest.so', b'ELF'*100)
                with self.assertRaisesRegex(d.DeliveryError, 'exactly the arm64-v8a and armeabi-v7a'):
                    d.inspect_apk_packaging(apk)


if __name__ == '__main__':
    unittest.main()
