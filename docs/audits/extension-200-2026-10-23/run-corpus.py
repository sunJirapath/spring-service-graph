"""Validate the 90 new repositories that extend the audited union to 200."""
import importlib.util
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
spec = importlib.util.spec_from_file_location('corpus_runner', HERE.parent/'boot4-80-2026-10-23/run-corpus.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
runner.OUT = ROOT/'build/audit200'
runner.QUALIFIED = runner.OUT/'qualified.json'
runner.CLONES = ROOT/'bench/audit200'
runner.TEMP = runner.OUT/'tmp'
runner.SUITE = 'boot4-extension-to-200'
runner.EXPECTED_COUNT = 90
runner.EXPECTED_PER_GROUP = None

if __name__ == '__main__':
    sys.exit(runner.main())
