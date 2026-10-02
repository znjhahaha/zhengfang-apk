import base64
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch, Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_distribution as d
from promote_release import verify_run, prepare_promotion_notes


def payload(channel='test'):
    return dict(channel=channel, revision=100, versionCode=98, versionName='1.0.98', minSdk=24,
        packageName='com.tyust.course', size=3, sha256='a'*64, sourceSha='b'*40, buildId='123',
        releaseNotes='更新内容', forceUpdate=False, publishedAt='2026-10-01T00:00:00Z',
        mirrors=[dict(id='cf', name='自有镜像', url='https://dl-test.hidisiwa.xyz/a.apk')])


class ManifestTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.key = Path(cls.directory.name)/'test.pem'
        subprocess.run(['openssl','genpkey','-algorithm','EC','-pkeyopt','ec_paramgen_curve:P-256','-out',str(cls.key)], check=True, capture_output=True)
        public = subprocess.check_output(['openssl','pkey','-in',str(cls.key),'-pubout','-outform','DER'])
        cls.keys = {d.KEY_ID:base64.b64encode(public).decode()}
    @classmethod
    def tearDownClass(cls): cls.directory.cleanup()
    def signed(self, p=None):
        with patch.object(d, 'public_keys', return_value=self.keys):
            return d.sign_manifest(p or payload(), self.key)
    def test_exact_utf8_round_trip(self):
        envelope = self.signed()
        self.assertEqual(d.verify_manifest(envelope, 'test', self.keys), payload())
    def test_signature_tampering_rejected(self):
        env = self.signed(); env['signature'] = base64.b64encode(b'bad').decode()
        with self.assertRaises(d.DeliveryError): d.verify_manifest(env, 'test', self.keys)
    def test_payload_tampering_rejected(self):
        env = self.signed(); env['payload'] = base64.b64encode(b'{}').decode()
        with self.assertRaises(d.DeliveryError): d.verify_manifest(env, 'test', self.keys)
    def test_unknown_key_rejected(self):
        env = self.signed(); env['keyId'] = 'unknown'
        with self.assertRaises(d.DeliveryError): d.verify_manifest(env, 'test', self.keys)
    def test_channel_isolation(self):
        with self.assertRaises(d.DeliveryError): d.verify_manifest(self.signed(), 'stable', self.keys)
    def test_duplicate_keys_rejected(self):
        with self.assertRaises(d.DeliveryError): d.strict_json('{"versionCode":98,"versionCode":99}')
    def test_huge_envelope_rejected_even_when_dict(self):
        with self.assertRaises(d.DeliveryError): d.verify_manifest(dict(payload='x'*140000), keys=self.keys)
    def test_noninteger_version_rejected(self):
        for bad in [True, 98.0, '98', 0]:
            with self.subTest(bad=bad), self.assertRaises(d.DeliveryError): d.validate_payload(dict(payload(), versionCode=bad))
    def test_urls_reject_credentials_ports_and_http(self):
        for url in ['http://github.com/a','https://u:p@github.com/a','https://github.com:444/a','https://evil.example/a','https://github.com/a#x', 'https://github.com/a\n']:
            self.assertFalse(d.valid_url(url), url)
    def test_replay_and_same_version_replacement_rejected(self):
        p=payload()
        for change in [dict(revision=99),dict(versionCode=97,revision=101),dict(sha256='c'*64,revision=101),dict(releaseNotes='changed')]:
            with self.subTest(change=change), self.assertRaises(d.DeliveryError): d.ensure_forward(p,dict(p,**change))
    def test_same_payload_idempotent_and_new_mirror_revision_allowed(self):
        p=payload(); d.ensure_forward(p,p); d.ensure_forward(p,dict(p,revision=101))
    def test_display_text_escaped(self):
        page=d.render_index(dict(payload(),releaseNotes='<script>alert(1)</script>'))
        self.assertNotIn('<script>',page); self.assertIn('&lt;script&gt;',page)


class DeliveryTests(unittest.TestCase):
    def test_budget_deadline_caps_every_subprocess(self):
        clock=Mock(return_value=10); b=d.Budget(5,clock); clock.return_value=13
        self.assertEqual(b.remaining(120),2)
        clock.return_value=15
        with self.assertRaises(d.DeliveryError): b.remaining(1)
    def test_timeout_becomes_redacted_stage_error(self):
        b=d.Budget()
        with patch.object(d.subprocess,'run',side_effect=subprocess.TimeoutExpired('secret',1)), self.assertRaisesRegex(d.DeliveryError,'upload: time limit'):
            b.run(['anything'],'upload',1)
        self.assertEqual(b.events[0]['result'],'timeout')
    def test_cli_error_does_not_print_credentials(self):
        with patch.object(d.subprocess,'run',return_value=subprocess.CompletedProcess([],1,b'token private',b'secret')), self.assertRaisesRegex(d.DeliveryError,'command exited 1') as error:
            d.Budget().run(['anything'],'upload')
        self.assertNotIn('secret',str(error.exception))
    def test_lost_upload_response_checked_before_repeat(self):
        action=Mock(side_effect=d.DeliveryError('timeout')); verify=Mock()
        d.verify_with_recovery(action,verify,{'stages':[]},d.Budget())
        self.assertEqual(action.call_count,1); verify.assert_called_once()
    def test_transient_failure_retried_only_once(self):
        action=Mock(); verify=Mock(side_effect=[d.DeliveryError('timeout'),None])
        d.verify_with_recovery(action,verify,{'stages':[]},d.Budget())
        self.assertEqual(action.call_count,2)
    def test_authentication_error_not_retried(self):
        action=Mock(side_effect=d.DeliveryError('provider codes 10000')); verify=Mock(side_effect=d.DeliveryError('not published'))
        with self.assertRaises(d.DeliveryError): d.verify_with_recovery(action,verify,{'stages':[]},d.Budget())
        self.assertEqual(action.call_count,1)
    def test_public_digest_mismatch_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            file=Path(root)/'apk'; file.write_bytes(b'bad')
            budget=Mock(); budget.remaining.return_value=10; budget.run.return_value=b'200'
            with self.assertRaises(d.DeliveryError): d.fetch('https://github.com/a',file,budget,expected=payload())
    def test_true_404_only_is_missing(self):
        with tempfile.TemporaryDirectory() as root:
            file=Path(root)/'file'; file.write_bytes(b'404')
            budget=Mock(); budget.remaining.return_value=10; budget.run.return_value=b'404'
            self.assertFalse(d.fetch('https://github.com/a',file,budget,allow_missing=True))
            self.assertFalse(file.exists())
            budget.run.return_value=b'403'
            with self.assertRaises(d.DeliveryError): d.fetch('https://github.com/a',file,budget,allow_missing=True)
    def test_static_25mib_boundary_no_paid_fallback(self):
        with tempfile.TemporaryDirectory() as root:
            file=Path(root)/'apk'; file.touch()
            with file.open('wb') as out: out.truncate(d.STATIC_LIMIT+1)
            budget=Mock()
            with self.assertRaisesRegex(d.DeliveryError,'25 MiB'): d.deploy(root,'test',budget)
            budget.run.assert_not_called()
            with file.open('wb') as out: out.truncate(d.STATIC_LIMIT)
            d.deploy(root,'test',budget); budget.run.assert_called_once()
    def test_three_rounds_and_final_manifest_separate(self):
        with tempfile.TemporaryDirectory() as root:
            report=Path(root)/'report'; p=payload(); env={'test':'signed'}
            def prepare(apk,receipt,channel,directory,budget): directory.mkdir(parents=True); return []
            def fetch(url,target,*args,**kwargs):
                if url.endswith('.json'): Path(target).write_text('{}')
                return True
            with patch.object(d,'prepare_static',side_effect=prepare), patch.object(d,'deploy') as deploy, patch.object(d,'fetch',side_effect=fetch), patch.object(d,'sign_manifest',return_value=env), patch.object(d,'verify_manifest',return_value=p), patch.object(d,'payload_for',return_value=p):
                d.deploy_release('unused',p,'test','notes',report,d.Budget(),rounds=3,publish_branch=False)
                self.assertEqual(deploy.call_count,4)
            result=json.loads(report.read_text()); self.assertEqual(result['result'],'verified')
            self.assertEqual(len(result['stages']),3)
    def test_cf_failure_does_not_publish_manifest(self):
        with tempfile.TemporaryDirectory() as root:
            report=Path(root)/'report'
            with patch.object(d,'prepare_static',side_effect=d.DeliveryError('unavailable')), patch.object(d,'publish_updates') as publish:
                with self.assertRaises(d.DeliveryError): d.deploy_release('unused',payload(),'test','notes',report,d.Budget(),publish_branch=False)
                publish.assert_not_called()
            self.assertEqual(json.loads(report.read_text())['result'],'failed')
    def test_old_repair_rejected_before_cf_upload(self):
        old=payload(); old['versionCode']=99
        with tempfile.TemporaryDirectory() as root, patch.object(d,'gh_api',return_value={'content':base64.b64encode(b'{}').decode()}), patch.object(d,'verify_manifest',return_value=old), patch.object(d,'deploy') as deploy:
            with self.assertRaisesRegex(d.DeliveryError,'older'): d.deploy_release('unused',payload(),'test','notes',Path(root)/'report',d.Budget())
            deploy.assert_not_called()
    def test_official_failure_stops_stable_publication(self):
        with tempfile.TemporaryDirectory() as root, patch.object(d,'fetch',side_effect=d.DeliveryError('GitHub unavailable')), patch.object(d,'deploy') as deploy:
            with self.assertRaises(d.DeliveryError): d.deploy_release('unused',payload('stable'),'stable','notes',Path(root)/'report',d.Budget(),publish_branch=False)
            deploy.assert_not_called()

    def test_missing_history_preserves_current_latest_apk_during_staging(self):
        with tempfile.TemporaryDirectory() as root:
            root=Path(root); apk=root/'new.apk'; apk.write_bytes(b'new')
            new=payload(); old=dict(new,versionCode=97,versionName='1.0.97',sha256='c'*64,revision=99)
            calls=[]
            def fetch(url,target,budget,expected=None,**kwargs):
                calls.append(url)
                if url.endswith('/history.json'): return False
                if url.endswith('/test.json'): Path(target).write_text(json.dumps(old)); return True
                Path(target).write_bytes(b'old'); return True
            with patch.object(d,'migration_pin',return_value=None), patch.object(d,'cf_project_exists',return_value=True), patch.object(d,'verify_manifest',side_effect=lambda e,c:e), patch.object(d,'fetch',side_effect=fetch):
                previous=d.prepare_static(apk,new,'test',root/'site',d.Budget())
            self.assertEqual(len(previous),1)
            self.assertTrue((root/'site/releases/1.0.97'/('c'*64)/'app-release.apk').exists())
            self.assertTrue((root/'site/releases/1.0.98'/('a'*64)/'app-release.apk').exists())
            self.assertEqual(json.loads((root/'site/test.json').read_text())['versionCode'],97)
    def test_legacy_pin_is_the_signed_original_test98(self):
        envelope, p = d.migration_pin('test')
        self.assertEqual(p['versionCode'], 98)
        self.assertEqual(p['sha256'], '501d8f8d0b6a8a738c4dc20f9e0cb05633e5d31c64ef8299138d39771e377c36')
        self.assertIsNone(d.migration_pin('stable'))

    def test_new_tests_preserve_legacy98_even_after_it_leaves_recent_history(self):
        with tempfile.TemporaryDirectory() as root:
            root=Path(root); apk=root/'new.apk'; apk.write_bytes(b'new')
            new=dict(payload(),versionCode=102,versionName='1.0.102',revision=102)
            current=dict(new,versionCode=101,versionName='1.0.101',revision=101,sha256='b'*64)
            older=dict(new,versionCode=100,versionName='1.0.100',revision=100,sha256='c'*64)
            pin=dict(new,versionCode=98,versionName='1.0.98',revision=98,sha256='d'*64)
            def fetch(url,target,budget,expected=None,**kwargs):
                if url.endswith('/history.json'): Path(target).write_text(json.dumps(dict(releases=[older])))
                elif url.endswith('/test.json'): Path(target).write_text(json.dumps(current))
                else: Path(target).write_bytes(b'old')
                return True
            with patch.object(d,'migration_pin',return_value=(pin,pin)), patch.object(d,'cf_project_exists',return_value=True), patch.object(d,'verify_manifest',side_effect=lambda e,c:e), patch.object(d,'fetch',side_effect=fetch):
                previous=d.prepare_static(apk,new,'test',root/'site',d.Budget())
            self.assertEqual([p['versionCode'] for _,p in previous],[101,98])
            self.assertTrue((root/'site/releases/1.0.98'/('d'*64)/'app-release.apk').exists())
            self.assertFalse((root/'site/releases/1.0.100').exists())
            self.assertEqual(json.loads((root/'site/test.json').read_text())['versionCode'],101)

    def test_missing_legacy_apk_aborts_staging_instead_of_deleting_its_public_url(self):
        with tempfile.TemporaryDirectory() as root:
            root=Path(root); apk=root/'new.apk'; apk.write_bytes(b'new')
            new=dict(payload(),versionCode=100,versionName='1.0.100')
            pin=dict(payload(),sha256='d'*64)
            with patch.object(d,'migration_pin',return_value=(pin,pin)), patch.object(d,'cf_project_exists',return_value=False), patch.object(d,'fetch',side_effect=d.DeliveryError('missing legacy APK')):
                with self.assertRaisesRegex(d.DeliveryError,'missing legacy APK'):
                    d.prepare_static(apk,new,'test',root/'site',d.Budget())
    def test_retention_downloads_only_previous_two_stable_versions(self):
        with tempfile.TemporaryDirectory() as root:
            root=Path(root); apk=root/'new.apk'; apk.write_bytes(b'new')
            new=payload('stable')
            old=[dict(new,versionCode=n,versionName='1.0.'+str(n),revision=n,sha256=str(n%10)*64) for n in [97,96,95]]
            def fetch(url,target,budget,expected=None,**kwargs):
                if url.endswith('/history.json'): Path(target).write_text(json.dumps(dict(releases=old)))
                elif url.endswith('/stable.json'): Path(target).write_text(json.dumps(old[0]))
                elif url.endswith('/announcement.json'): Path(target).write_text('{"announcements":[]}')
                else: Path(target).write_bytes(b'old')
                return True
            with patch.object(d,'cf_project_exists',return_value=True), patch.object(d,'verify_manifest',side_effect=lambda e,c:e), patch.object(d,'fetch',side_effect=fetch):
                previous=d.prepare_static(apk,new,'stable',root/'site',d.Budget())
            self.assertEqual([p['versionCode'] for _,p in previous],[97,96])
            self.assertFalse((root/'site/releases/1.0.95').exists())

    def test_android_build_tools_36_certificate_format(self):
        self.assertEqual(d.apk_signer_digests('Signer #1 certificate SHA-256 digest: '+'a'*64),{'a'*64})
    def test_android_build_tools_37_certificate_format(self):
        self.assertEqual(d.apk_signer_digests('V2 Signer: certificate SHA-256 digest: '+'a'*64),{'a'*64})
    def test_repeated_verified_schemes_are_one_identity(self):
        self.assertEqual(d.apk_signer_digests('V2 Signer: certificate SHA-256 digest: '+'a'*64+'\nV3 Signer: certificate SHA-256 digest: '+'a'*64),{'a'*64})
    def test_additional_signers_are_not_ignored(self):
        self.assertEqual(d.apk_signer_digests('Signer #1 certificate SHA-256 digest: '+'a'*64+'\nSigner #2 certificate SHA-256 digest: '+'b'*64),{'a'*64,'b'*64})
    def test_source_stamp_is_not_an_apk_signer(self):
        self.assertEqual(d.apk_signer_digests('Source Stamp Signer certificate SHA-256 digest: '+'a'*64),set())


class PromotionTests(unittest.TestCase):
    def setUp(self):
        self.run=dict(event='workflow_dispatch',status='completed',conclusion='success',repository={'full_name':d.REPO},head_repository={'full_name':d.REPO},path='.github/workflows/release.yml',head_sha='a'*40,id=123)
        self.receipt=dict(sourceSha='a'*40,buildId='123',tests=dict(tests=10,failures=0,errors=0))
    def test_matching_successful_run(self): verify_run(self.run,self.receipt)
    def test_failed_or_incomplete_run_rejected(self):
        for key,value in [('conclusion','failure'),('status','in_progress'),('event','pull_request'),('head_sha','b'*40),('path','other.yml')]:
            with self.subTest(key=key), self.assertRaises(d.DeliveryError): verify_run(dict(self.run,**{key:value}),self.receipt)
    def test_fork_artifact_rejected(self):
        with self.assertRaises(d.DeliveryError): verify_run(dict(self.run,head_repository={'full_name':'evil/fork'}),self.receipt)
    def test_missing_or_failed_checks_rejected(self):
        for tests in [{},dict(tests=1,failures=1),dict(tests=1,errors=1)]:
            with self.assertRaises(d.DeliveryError): verify_run(self.run,dict(self.receipt,tests=tests))

    def test_stable_wording_preserves_original_apk_and_receipt(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); (root/'release-notes').mkdir()
            (root/'release-notes/v1.0.101.md').write_text('## notes\n测试说明\n\n## stable notes\n正式说明\n\n## validation\n内部证据\n')
            (root/'release-notes.txt').write_text('测试说明\n')
            (root/'app-release.apk').write_bytes(b'signed-original')
            (root/'receipt.json').write_bytes(b'original-receipt')
            prepare_promotion_notes('v1.0.101', root, root)
            self.assertEqual((root/'release-notes.txt').read_text(), '正式说明\n')
            self.assertEqual((root/'app-release.apk').read_bytes(), b'signed-original')
            self.assertEqual((root/'receipt.json').read_bytes(), b'original-receipt')

    def test_legacy_promotion_without_stable_section_retains_artifact_notes(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); (root/'release-notes').mkdir()
            (root/'release-notes.txt').write_text('原始说明\n')
            prepare_promotion_notes('v1.0.100', root, root)
            (root/'release-notes/v1.0.100.md').write_text('## notes\n归档\n')
            prepare_promotion_notes('v1.0.100', root, root)
            self.assertEqual((root/'release-notes.txt').read_text(), '原始说明\n')

    def test_empty_stable_section_fails_without_replacing_artifact_notes(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); (root/'release-notes').mkdir()
            (root/'release-notes/v1.0.101.md').write_text('## stable notes\n\n## validation\n内部证据\n')
            (root/'release-notes.txt').write_text('原始说明\n')
            with self.assertRaises(d.DeliveryError): prepare_promotion_notes('v1.0.101', root, root)
            self.assertEqual((root/'release-notes.txt').read_text(), '原始说明\n')

if __name__ == '__main__': unittest.main()
