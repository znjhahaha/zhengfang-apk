import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from plugin_audit_job import self_test_files


class PublicAuditCoverageTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.kit = Path(self.tmp.name)
        self.add_file('security/test-isolated.mjs')

    def add_file(self, name):
        file = self.kit/name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text('// synthetic source\n')

    def test_older_toolchains_keep_their_existing_optional_suites(self):
        self.assertEqual(self_test_files(self.kit, '3.2.9'), ['security/test-isolated.mjs'])
        self.add_file('tests/wiki-examples.test.mjs')
        self.assertIn('tests/wiki-examples.test.mjs', self_test_files(self.kit, '3.4.0'))

    def test_new_sdk_cannot_silently_omit_academic_or_host_regressions(self):
        with self.assertRaisesRegex(RuntimeError, 'SELF_TEST_FILES_MISSING'):
            self_test_files(self.kit, '3.5.0')
        names = ['eams', 'chaoxing-academic', 'new-academic-templates', 'protocol-platform',
                 'platform-release', 'userscript-head', 'native-pattern', 'gecko-runtime']
        for name in names:
            self.add_file('tests/'+name+'.test.mjs')
        files = self_test_files(self.kit, '3.5.0')
        self.assertEqual(len(files), 9)
        self.assertEqual(len(set(files)), len(files))
        with self.assertRaisesRegex(RuntimeError, 'SELF_TEST_FILES_MISSING'):
            self_test_files(self.kit, '3.6.0')
        self.add_file('tests/native-navigation.test.mjs')
        self.assertIn('tests/native-navigation.test.mjs', self_test_files(self.kit, '3.6.0'))
        with self.assertRaisesRegex(RuntimeError, 'SELF_TEST_FILES_MISSING'):
            self_test_files(self.kit, '3.6.2')
        self.add_file('tests/foreground-script.test.mjs')
        self.add_file('tests/security-policy.test.mjs')
        self.assertIn('tests/foreground-script.test.mjs', self_test_files(self.kit, '3.6.2'))
        self.assertIn('tests/security-policy.test.mjs', self_test_files(self.kit, '3.6.2'))
        with self.assertRaisesRegex(RuntimeError, 'SELF_TEST_FILES_MISSING'):
            self_test_files(self.kit, '3.7.0')
        for name in ['kingo', 'kingo-des', 'lntu']:
            self.add_file('tests/'+name+'.test.mjs')
        files = self_test_files(self.kit, '3.7.0')
        self.assertTrue({'tests/kingo.test.mjs', 'tests/kingo-des.test.mjs', 'tests/lntu.test.mjs'}.issubset(files))
        self.assertEqual(len(set(files)), len(files))
        with self.assertRaisesRegex(RuntimeError, 'SELF_TEST_FILES_MISSING'):
            self_test_files(self.kit, '3.7.1')
        self.add_file('tests/auth-scope.test.mjs')
        self.assertIn('tests/auth-scope.test.mjs', self_test_files(self.kit, '3.7.1'))
        self.assertNotIn('tests/auth-scope.test.mjs', self_test_files(self.kit, '3.7.0'))
        with self.assertRaisesRegex(RuntimeError, 'SELF_TEST_FILES_MISSING'):
            self_test_files(self.kit, '3.8.0')
        for name in ['widget-date-host', 'ui-pickers', 'widget-plugin', 'qz-login-77', 'ahut-vpn', 'exam-identity']:
            self.add_file('tests/'+name+'.test.mjs')
        files = self_test_files(self.kit, '3.8.0')
        self.assertTrue({'tests/widget-date-host.test.mjs', 'tests/ui-pickers.test.mjs',
                         'tests/widget-plugin.test.mjs', 'tests/qz-login-77.test.mjs',
                         'tests/ahut-vpn.test.mjs', 'tests/exam-identity.test.mjs'}.issubset(files))
        self.assertEqual(len(files),len(set(files)))
        (self.kit/'tests/kingo-des.test.mjs').unlink()
        with self.assertRaisesRegex(RuntimeError, 'SELF_TEST_FILES_MISSING'):
            self_test_files(self.kit, '3.7.1')
        (self.kit/'tests/chaoxing-academic.test.mjs').unlink()
        with self.assertRaisesRegex(RuntimeError, 'SELF_TEST_FILES_MISSING'):
            self_test_files(self.kit, '3.5.1')


if __name__ == '__main__':
    unittest.main()
