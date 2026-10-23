"""Acquire the extension's pinned source snapshots without running foreign builds."""
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
spec = importlib.util.spec_from_file_location('source_acquisition', HERE.parent/'boot4-80-2026-10-23/acquire.py')
acquisition = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acquisition)
acquisition.OUT = ROOT/'build/audit200'
acquisition.CLONES = ROOT/'bench/audit200'
acquisition.TEMP = acquisition.OUT/'tmp'
acquisition.ENV.update(TEMP=str(acquisition.TEMP), TMP=str(acquisition.TEMP), TMPDIR=str(acquisition.TEMP))

def prepare():
    for path in (acquisition.TEMP, acquisition.CLONES, acquisition.OUT/'qualification'):
        path.mkdir(parents=True, exist_ok=True)

def manifest():
    rows = json.loads((HERE/'manifest.json').read_text(encoding='utf-8'))
    assert len(rows) == 90 and len({r['repository'].casefold() for r in rows}) == 90
    assert all(sum(r['build_system'] == tool for r in rows) == 45 for tool in ('maven','gradle'))
    for row in rows:
        row['tool'] = row['build_system']
        row['version_file'] = row['boot_evidence']['file']
        row['version_line'] = row['boot_evidence']['line']
        assert len(row['commit']) == 40 and int(row['boot_version'].split('.')[0]) >= 4
    return rows

if __name__ == '__main__':
    prepare()
    with ThreadPoolExecutor(max_workers=2) as pool:
        rows = list(pool.map(acquisition.acquire, manifest()))
    (acquisition.OUT/'qualified.json').write_text(json.dumps(rows,indent=2)+'\n',encoding='utf-8')
    print('Qualified',sum(r['qualified'] for r in rows),'of',len(rows))
