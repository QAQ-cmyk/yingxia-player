"""Package source and review artifacts, excluding local tools and signing material."""
from pathlib import Path
import hashlib
import zipfile
import argparse

parser = argparse.ArgumentParser()
parser.add_argument('--version', default='1.2.1')
version = parser.parse_args().version
if not __import__('re').fullmatch(r'\d+\.\d+\.\d+', version):
    parser.error('version must be major.minor.patch')

root = Path(__file__).resolve().parents[1]
delivery = root / 'delivery'
destination = delivery / f'YingXia-{version}-source.zip'
roots = ['.github', 'app', 'shared-protocol', 'server', 'gradle', 'scripts', 'docs', 'delivery', 'fastlane']
files = {root / name for name in ['.gitignore', '.gitattributes', 'README.md', 'README.zh-CN.md', 'CHANGELOG.md', 'CONTRIBUTING.md', 'SECURITY.md', 'LICENSE',
    'settings.gradle.kts', 'build.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat']}
for folder in roots:
    for path in (root / folder).rglob('*'):
        if not path.is_file():
            continue
        relative = path.relative_to(root)
        if any(part in {'build', '__pycache__', '.tools', '.gradle', '.git'} for part in relative.parts):
            continue
        if path.suffix.lower() in {'.apk', '.zip', '.idsig', '.jks', '.keystore', '.p12', '.pyc'}:
            continue
        if path.name == 'local.properties' or path.name.startswith('SHA256SUMS'):
            continue
        files.add(path)
with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for path in sorted(files):
        archive.write(path, 'YingXia/' + path.relative_to(root).as_posix())
with zipfile.ZipFile(destination) as archive:
    assert archive.testzip() is None
    assert not any('/.tools/' in name or name.endswith(('.p12', '.jks', '.keystore')) for name in archive.namelist())
targets = [delivery / f'YingXia-{version}.apk']
sync_apk = delivery / f'YingXia-{version}-sync.apk'
if sync_apk.exists():
    targets.append(sync_apk)
targets.append(destination)
lines = [f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}' for path in targets]
(delivery / 'SHA256SUMS.txt').write_text('\n'.join(lines) + '\n', encoding='utf-8')
(delivery / f'SHA256SUMS-{version}.txt').write_text('\n'.join(lines) + '\n', encoding='utf-8')
print(f'Created source archive: {len(files)} files, {destination.stat().st_size:,} bytes')
print('\n'.join(lines))
