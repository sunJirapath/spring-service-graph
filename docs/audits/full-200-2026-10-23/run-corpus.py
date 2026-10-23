"""Validate all frozen 200 pins with one installed scanner build; resume by output hashes."""
import importlib.util
import json
from pathlib import Path
import sys
sys.dont_write_bytecode=True

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
spec=importlib.util.spec_from_file_location('corpus_runner',HERE.parent/'boot4-80-2026-10-23/run-corpus.py')
runner=importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
runner.OUT=ROOT/'build/full200'
runner.QUALIFIED=runner.OUT/'qualified.json'
runner.CLONES=runner.OUT/'repos'
runner.TEMP=runner.OUT/'tmp'
runner.SUITE='full-pinned-200'

def load_candidates(path):
    rows=json.loads(path.read_text(encoding='utf-8'))
    expected=json.loads((HERE/'manifest.json').read_text(encoding='utf-8'))
    pins={r['repository']:(r['commit'],r['tool'],r['tier'],r['java_files'],r.get('boot_version')) for r in expected}
    assert len(rows)==len(pins)==200 and len({r['repository'] for r in rows})==200
    for row in rows:
        assert row.get('qualified') and row.get('path')
        assert (row['commit'],row['tool'],row['tier'],row['java_files'],row.get('boot_version'))==pins[row['repository']]
        if row.get('boot_version'):
            assert int(row['boot_version'].split('.')[0])>=4
    return rows

runner.load_candidates=load_candidates

if __name__=='__main__':
    sys.exit(runner.main())
