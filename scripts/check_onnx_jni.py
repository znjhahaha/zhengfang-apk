#!/usr/bin/env python3
"""Reject a minified build whose ONNX classes cannot be found from native JNI."""
import argparse
import json
from pathlib import Path
import re


REQUIRED = {
    'ai.onnxruntime.TensorInfo',
    'ai.onnxruntime.TensorInfo$OnnxTensorType',
    'ai.onnxruntime.OnnxJavaType',
    'ai.onnxruntime.OnnxTensor',
    'ai.onnxruntime.OrtException',
}


def check_mapping(text):
    classes = {}
    synthesized = set()
    current = None
    for line in text.splitlines():
        match = re.fullmatch(r'([^\s]+) -> ([^\s:]+):', line)
        if match:
            name, target = match.groups()
            current = name if name.startswith('ai.onnxruntime.') else None
            if current:
                classes[current] = target
        elif current and line.startswith('#'):
            # Only unindented, class-level R8 metadata identifies generated
            # helpers. A synthesized method must not exempt its real JNI class.
            try:
                metadata = json.loads(line[1:])
            except (ValueError, TypeError):
                continue
            if isinstance(metadata, dict) and metadata.get('id') == 'com.android.tools.r8.synthesized':
                synthesized.add(current)
    missing = REQUIRED - classes.keys()
    exempt = synthesized - REQUIRED
    renamed = {name for name, target in classes.items() if name != target and name not in exempt}
    if missing or renamed:
        raise ValueError('ONNX JNI classes removed or renamed: ' + ', '.join(sorted(missing | renamed)))
    return len(classes.keys() - exempt)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mapping', type=Path)
    args = parser.parse_args()
    count = check_mapping(args.mapping.read_text(encoding='utf-8'))
    print(f'ONNX JNI class names preserved in minified APK: {count}')
