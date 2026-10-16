"""Package source and runnable JAR, excluding runtime data and dependency caches."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import hashlib
import sys

root = Path(__file__).resolve().parents[1]
output = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root.parent / 'hengzhang-reconciliation-center.zip'
if root in output.parents:
    raise SystemExit('Choose an output file outside the project directory.')
jar = root / 'app' / 'reconciliation-center.jar'
if not jar.is_file():
    raise SystemExit('Build the runnable JAR before packaging.')
excluded = {'node_modules', 'target', 'data', 'logs', 'artifacts', 'dist', '.git', '__pycache__', 'playwright-report', 'test-results'}
with ZipFile(output, 'w', ZIP_DEFLATED, compresslevel=6) as archive:
    for path in sorted(root.rglob('*')):
        relative = path.relative_to(root)
        if not path.is_file() or any(part in excluded for part in relative.parts):
            continue
        if path.name == '.env' or path.suffix in {'.log', '.pid', '.zip'}:
            continue
        archive.write(path, Path(root.name) / relative)
digest = hashlib.sha256(output.read_bytes()).hexdigest()
output.with_suffix(output.suffix + '.sha256').write_text(f'{digest}  {output.name}\n', encoding='utf-8')
print(f'{output}\n{output.stat().st_size:,} bytes\nSHA256 {digest}')
