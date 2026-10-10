#!/usr/bin/env python3
import os
from pathlib import Path
import re

def prepare(tag, root=Path('.')):
    if not re.fullmatch(r'v1\.0\.[1-9][0-9]*', tag):
        raise ValueError('Expected v1.0.N version')
    notes = root/'release-notes'/f'{tag}.md'
    text = notes.read_text(encoding='utf-8')
    if '## notes\n' not in text:
        raise ValueError('Missing archived release notes')
    content = text.split('## notes\n', 1)[1].split('\n## ', 1)[0].strip()
    if not content:
        raise ValueError('Empty release notes')
    (root/'release-notes.txt').write_text(content+'\n', encoding='utf-8')
    (root/'app/version.properties').write_text(f'# Build version selected by test workflow\nVERSION_NAME={tag[1:]}\nVERSION_CODE={tag.rsplit(".",1)[1]}\n', encoding='utf-8')

if __name__ == '__main__':
    prepare(os.environ['TAG'])
