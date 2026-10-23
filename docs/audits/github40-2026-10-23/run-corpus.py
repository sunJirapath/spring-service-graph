"""Re-acquire the exact 40 public commits and repeat the source-only scans.

Requires git, JDK 21 and `gradlew.bat installDist`. Writes only beneath this
workspace's bench/github40-rerun and build/github40-rerun on E:. Serial scans
use a 768 MB heap. Does not build any downloaded project or change global TEMP.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CLONES = ROOT / 'bench/github40-rerun'
OUT = ROOT / 'build/github40-rerun'
TEMP = OUT / 'tmp'
TEMP.mkdir(parents=True, exist_ok=True)
CLONES.mkdir(parents=True, exist_ok=True)
os.environ.update(TEMP=str(TEMP), TMP=str(TEMP), GIT_TERMINAL_PROMPT='0')
JAVA = ['java', '-Xmx768m', '-Djava.io.tmpdir=' + str(TEMP), '-cp',
        str(ROOT / 'build/install/ssg/lib/*'), 'io.github.sunjirapath.ssg.Main']
rows = json.loads((HERE / 'manifest.json').read_text(encoding='utf-8'))
assert len(rows) == 40 and all(sum(r['tier'] == t for r in rows) == 10 for t in ['small','medium','large','huge'])
results = []

def git(repo, *args):
    return subprocess.check_output(['git','-c','core.longpaths=true','-C',str(repo),*args],
                                   text=True, encoding='utf-8', errors='replace').strip()

def hashes(folder):
    return {f.relative_to(folder).as_posix(): hashlib.sha256(f.read_bytes()).hexdigest()
            for f in folder.rglob('*') if f.is_file()}

for row in rows:
    slug = row['repository'].replace('/','--')
    repo = CLONES / slug
    if not repo.exists():
        repo.mkdir()
        git(repo,'init','-q')
        git(repo,'remote','add','origin','https://github.com/'+row['repository']+'.git')
        git(repo,'fetch','-q','--depth','1','origin',row['commit'])
        git(repo,'checkout','-q','FETCH_HEAD')
    assert git(repo,'rev-parse','HEAD') == row['commit'], f'Unexpected commit: {repo}'
    assert not git(repo,'status','--porcelain'), f'Dirty checkout: {repo}'
    measurements = []
    for run in [1,2]:
        output = OUT / ('run'+str(run)) / slug
        output.mkdir(parents=True, exist_ok=True)
        started = time.monotonic()
        p = subprocess.run(JAVA + ['scan','--repos',str(repo),'--out',str(output)],
                           capture_output=True,text=True,encoding='utf-8',timeout=600)
        elapsed = round(time.monotonic()-started,3)
        (output.parent / (slug+'.stderr.log')).write_text(p.stderr,encoding='utf-8')
        assert p.returncode == 0,(row['repository'],run,p.returncode,p.stderr)
        measurements.append(dict(run=run,seconds=elapsed,files=hashes(output)))
    result = dict(repository=row['repository'],commit=row['commit'],measurements=measurements,
                  deterministic=measurements[0]['files']==measurements[1]['files'])
    assert result['deterministic'],row['repository']
    results.append(result)
    (OUT / 'results.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
    print(row['tier'],row['repository'],'deterministic',flush=True)
