"""Repeat the two pinned public regressions without executing their applications."""
import importlib.util
from pathlib import Path
import sys

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
spec=importlib.util.spec_from_file_location('audit_runner',HERE.parent/'boot4-80-2026-10-23/run-corpus.py')
runner=importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
runner.OUT=ROOT/'build/final-bugs'
runner.QUALIFIED=runner.OUT/'qualified.json'
runner.CLONES=runner.OUT/'repos'
runner.TEMP=runner.OUT/'tmp'
runner.SUITE='remaining-audit-fixes'
runner.EXPECTED_COUNT=2
runner.EXPECTED_PER_GROUP=None

if __name__=='__main__':
    sys.exit(runner.main())
