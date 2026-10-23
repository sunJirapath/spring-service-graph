"""Check frozen input validation and campaign-aware private history without network."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
sys.dont_write_bytecode=True

ROOT=Path(__file__).resolve().parents[3]
WORK=ROOT/'build/full200'
path=ROOT/'docs/audits/full-200-2026-10-23/run-corpus.py'
spec=importlib.util.spec_from_file_location('wrapper',path)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
r=module.runner
manifest=json.loads((path.parent/'manifest.json').read_text())
rows=[dict(v,qualified=True,path=str(WORK/'repos'/v['repository'].replace('/','--'))) for v in manifest]
fixture=WORK/'selfcheck-input.json'
WORK.mkdir(parents=True,exist_ok=True)
checks=0
def check(values,valid):
    global checks
    fixture.write_text(json.dumps(values),encoding='utf-8')
    try:module.load_candidates(fixture)
    except (AssertionError,KeyError,ValueError):assert not valid
    else:assert valid
    checks+=1
check(rows,True)
check(rows[:-1],False)
dup=copy.deepcopy(rows);dup[-1]=dup[0];check(dup,False)
bad=copy.deepcopy(rows);bad[0]['commit']='0'*40;check(bad,False)
boot=copy.deepcopy(rows);boot[0]['boot_version']='3.0.0';check(boot,False)
unqualified=copy.deepcopy(rows);unqualified[0]['qualified']=False;check(unqualified,False)
try:r.source_checkout(dict(path=str(ROOT/'outside'),commit='0'*40,repository='outside/test'))
except RuntimeError:checks+=1
else:raise AssertionError('Checkout containment failed')
r.REGISTRY=WORK/'selfcheck-registry.json'
r.REGISTRY.write_text(json.dumps(dict(schema_version=1,runs=[])),encoding='utf-8')
row=dict(rows[0],build_systems=['Maven','Gradle'])
result=dict(scans=[dict(exit_code=0,started_at='test'),dict(exit_code=0,started_at='test')],
    finished_at='test',deterministic_output=True,status='passed',source_checkout='build/full200/selfcheck',
    result_file='build/full200/selfcheck-result.json')
r.SUITE='selfcheck-first'
r.append_registry(row,result,'a'*64,'b'*64,'c'*40)
r.append_registry(row,result,'a'*64,'b'*64,'c'*40)
state=json.loads(r.REGISTRY.read_text());assert len(state['runs'])==1;checks+=1
r.SUITE='selfcheck-second'
r.append_registry(row,result,'a'*64,'b'*64,'c'*40)
state=json.loads(r.REGISTRY.read_text());assert len(state['runs'])==2;checks+=1
assert all(v['build_system']==['Maven','Gradle'] for v in state['runs']);checks+=1
summary=dict(checks=checks,passed=checks,network=False,private_history_modified=False,
    covers=['200 frozen pins including legacy versions','wrong count','duplicates','pin drift','Boot evidence drift',
            'qualification','checkout containment','same-campaign resume deduplication','different campaign rerun history','mixed build descriptors'])
(WORK/'harness-selfcheck.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
print(summary)
