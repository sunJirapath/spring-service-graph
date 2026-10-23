"""Acquire the two pinned source snapshots used for the remaining bug regressions."""
import importlib.util
import json
from pathlib import Path

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
spec=importlib.util.spec_from_file_location('audit_acquire',HERE.parent/'boot4-80-2026-10-23/acquire.py')
acquisition=importlib.util.module_from_spec(spec)
spec.loader.exec_module(acquisition)
acquisition.OUT=ROOT/'build/final-bugs'
acquisition.CLONES=acquisition.OUT/'repos'
acquisition.TEMP=acquisition.OUT/'tmp'
acquisition.ENV.update(TEMP=str(acquisition.TEMP),TMP=str(acquisition.TEMP),TMPDIR=str(acquisition.TEMP))

if __name__=='__main__':
    for path in (acquisition.CLONES,acquisition.TEMP,acquisition.OUT/'qualification'):
        path.mkdir(parents=True,exist_ok=True)
    rows=json.loads((HERE/'manifest.json').read_text(encoding='utf-8'))
    assert len(rows)==2 and len({r['repository'].casefold() for r in rows})==2
    for row in rows:
        row.update(tool=row['build_system'],version_file=row['boot_evidence']['file'],version_line=row['boot_evidence']['line'])
    results=[acquisition.acquire(row) for row in rows]
    (acquisition.OUT/'qualified.json').write_text(json.dumps(results,indent=2)+'\n',encoding='utf-8')
    assert all(r['qualified'] for r in results)
