from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_onnx_jni import REQUIRED, check_mapping


class OnnxJniMappingTests(unittest.TestCase):
    def mapping(self, names=REQUIRED):
        return '\n'.join(f'{name} -> {name}:' for name in sorted(names))

    def test_native_class_names_survive_minification(self):
        self.assertEqual(check_mapping(self.mapping()), len(REQUIRED))

    def test_tensor_metadata_rename_that_caused_native_abort_is_rejected(self):
        text = self.mapping().replace('-> ai.onnxruntime.TensorInfo:', '-> a.b:')
        with self.assertRaisesRegex(ValueError, 'TensorInfo'):
            check_mapping(text)

    def test_removed_jni_only_class_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'TensorInfo'):
            check_mapping(self.mapping(REQUIRED - {'ai.onnxruntime.TensorInfo'}))

    def test_additional_onnx_classes_cannot_be_obfuscated(self):
        with self.assertRaisesRegex(ValueError, 'OnnxSequence'):
            check_mapping(self.mapping() + '\nai.onnxruntime.OnnxSequence -> a.c:')

    def test_r8_generated_lambda_is_not_a_jni_class(self):
        text = self.mapping() + '''
ai.onnxruntime.providers.StringConfigProviderOptions$$ExternalSyntheticLambda0 -> ai.onnxruntime.providers.a:
# {"id":"sourceFile","fileName":"R8$$SyntheticClass"}
# {"id":"com.android.tools.r8.synthesized"}
    1:7:java.lang.Object apply(java.lang.Object):0:0 -> apply
      # {"id":"com.android.tools.r8.synthesized"}
'''
        self.assertEqual(check_mapping(text), len(REQUIRED))

    def test_synthetic_method_does_not_exempt_its_real_class(self):
        text = self.mapping() + '''
ai.onnxruntime.OnnxSequence -> a.c:
    1:7:java.lang.Object getValue():0:0 -> a
      # {"id":"com.android.tools.r8.synthesized"}
'''
        with self.assertRaisesRegex(ValueError, 'OnnxSequence'):
            check_mapping(text)

    def test_required_jni_class_cannot_be_exempted_as_synthetic(self):
        text = self.mapping().replace(
            'ai.onnxruntime.TensorInfo -> ai.onnxruntime.TensorInfo:',
            'ai.onnxruntime.TensorInfo -> a.b:\n# {"id":"com.android.tools.r8.synthesized"}')
        with self.assertRaisesRegex(ValueError, 'TensorInfo'):
            check_mapping(text)

    def test_crlf_mapping_is_supported(self):
        self.assertEqual(check_mapping(self.mapping().replace('\n', '\r\n')), len(REQUIRED))


if __name__ == '__main__':
    unittest.main()
