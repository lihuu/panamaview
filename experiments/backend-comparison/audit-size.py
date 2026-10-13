#!/usr/bin/env python3
"""Read-only audit of built artifacts, shared frontend and embedded native libraries."""
import gzip
import hashlib
import json
import mmap
import zipfile
from pathlib import Path

base = Path(__file__).resolve().parent
target = base / 'target'
artifacts = {
    'ffm': target / 'ffm/dist/ffm-comparison',
    'javafx-webview': target / 'javafx/dist/javafx-comparison',
    'javafx-ui': base.parent / 'javafx-native-ui/target/dist/javafx-native-ui',
}
report = {'compression': 'gzip level 6, mtime=0; single executable, not an installer',
          'artifacts': {}, 'javafx_native_libraries': []}
for name, path in artifacts.items():
    data = path.read_bytes()
    report['artifacts'][name] = {
        'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
        'gzip_bytes': len(gzip.compress(data, compresslevel=6, mtime=0)),
    }

frontend = []
for path in sorted((target / 'ffm/classes/web').rglob('*')):
    if path.is_file():
        relative = path.relative_to(target / 'ffm/classes/web')
        assert path.read_bytes() == (target / 'javafx/classes/web' / relative).read_bytes(), relative
        frontend.append({'path': str(relative), 'bytes': path.stat().st_size,
                         'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
report['identical_frontend'] = frontend
report['frontend_bytes_excluding_framework_bridge_and_host_injection'] = sum(row['bytes'] for row in frontend)

classpath = (target / 'javafx/classpath-patched.txt').read_text().strip().split(':')
with artifacts['javafx-webview'].open('rb') as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as image:
    for artifact in classpath:
        with zipfile.ZipFile(artifact) as jar:
            for entry in jar.infolist():
                if entry.filename.endswith('.dylib'):
                    library = jar.read(entry)
                    offset = image.find(library)
                    report['javafx_native_libraries'].append({
                        'jar': Path(artifact).name, 'library': entry.filename,
                        'raw_bytes': entry.file_size, 'jar_compressed_bytes': entry.compress_size,
                        'sha256': hashlib.sha256(library).hexdigest(),
                        'byte_identical_embedded_offset': offset,
                    })
                    if entry.filename == 'libjfxwebkit.dylib':
                        assert offset >= 0, 'The expected complete WebKit resource is absent'
original = next(p for p in (target / 'javafx/classpath.txt').read_text().strip().split(':')
                if p.endswith('javafx-web-25.0.2-mac-aarch64.jar'))
with zipfile.ZipFile(original) as jar:
    report['original_unpatched_webkit_bytes'] = jar.getinfo('libjfxwebkit.dylib').file_size
report['notes'] = [
    'Offset -1 means no complete byte-identical copy found by this check; it is not a general resource exclusion proof.',
    'The controls application has a different UI workload. These sizes are not a runtime performance ranking.',
]
output = target / 'size-audit.json'
output.write_text(json.dumps(report, indent=2) + '\n')
print(output)
for name, row in report['artifacts'].items():
    print(f"{name}: raw={row['bytes'] / 1048576:.2f} MiB, gzip={row['gzip_bytes'] / 1048576:.2f} MiB")
