"""Pipeline two source acquisitions with serial scanner JVMs; retain per-repository checkpoints."""
from concurrent.futures import ThreadPoolExecutor,as_completed
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import time
import sys
sys.dont_write_bytecode=True

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]

def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path)
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value)
    return value

a=module('full_acquire',HERE/'acquire.py')
r=module('full_runner',HERE/'run-corpus.py').runner
c=module('full_compare',HERE/'compare.py')
EXPECTED_UNSUPPORTED={f'shuigedeng/taotao-cloud-{name}' for name in ('sys','goods','order','member','message')}

def classify(row,result):
    if result['status']=='passed':return 'passed'
    if (row['repository'] in EXPECTED_UNSUPPORTED and result.get('execution_status')=='passed'
        and result.get('deterministic_output') and result.get('errors')==['scan returned zero services']
        and result.get('graph_validation',{}).get('nodes')==0):
        return 'expected_unsupported'
    return 'unexpected_failure'

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phase',choices=('before','after'),default='after')
    args=parser.parse_args()
    a.prepare()
    rows=a.manifest()
    scanner_hash,distribution,commit,state,jars=r.installed_scanner()
    expected=json.loads((HERE/'scanner-builds.json').read_text(encoding='utf-8'))[args.phase]
    assert scanner_hash==expected['scanner_jar_sha256'],'Installed scanner differs from selected build'
    assert distribution==expected['distribution_sha256']
    source_hashes=json.loads((ROOT/expected['source_inventory']).read_text(encoding='utf-8'))
    assert all(hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest for name,digest in source_hashes.items()),'Build input source drift'
    run_root=a.OUT/'runs'/distribution[:16]
    (run_root/'results').mkdir(parents=True,exist_ok=True)
    started=r.utc_now();summaries=[];qualified=[]
    with ThreadPoolExecutor(max_workers=2) as pool:
        pending={pool.submit(a.acquire,row):row for row in rows}
        for future in as_completed(pending):
            row=future.result()
            if not row['qualified']:
                time.sleep(2)
                row=a.acquire(pending[future])
            qualified.append(row)
            r.json_write_atomic(a.OUT/'qualified.json',sorted(qualified,key=lambda v:v['repository'].casefold()))
            r.json_write_atomic(a.OUT/'progress.json',dict(current=row['repository'],completed=len(summaries),started_at=r.utc_now()))
            print('SCANNING',len(summaries)+1,'/200',row['repository'],flush=True)
            if row['qualified']:
                try:
                    result=r.run_candidate(row,scanner_hash,distribution,commit,state,jars,run_root)
                    delta=c.compare(row,run_root/'run1'/r.slug_for(row)/'graph.json',run_root/'changes'/(r.slug_for(row)+'.json'))
                    summary=dict(repository=row['repository'],commit=row['commit'],classification=classify(row,result),
                        raw_status=result['status'],semantic_change=delta['semantic_change'],result_file=result['result_file'],errors=result['errors'])
                except Exception as exc:
                    summary=dict(repository=row['repository'],commit=row['commit'],classification='unexpected_failure',errors=[str(exc)])
            else:
                summary=dict(repository=row['repository'],commit=row['commit'],classification='acquisition_failed',errors=[row['error']])
            summaries.append(summary)
            r.json_write_atomic(run_root/'campaign.json',dict(suite=r.SUITE,started_at=started,updated_at=r.utc_now(),
                scanner_jar_sha256=scanner_hash,distribution_sha256=distribution,scanner_commit=commit,
                expected_repositories=200,completed=len(summaries),results=sorted(summaries,key=lambda v:v['repository'].casefold())))
            print('RESULT',row['repository'],summary['classification'],'changed='+str(summary.get('semantic_change')),flush=True)
    r.json_write_atomic(a.OUT/'progress.json',dict(current=None,completed=len(summaries),finished_at=r.utc_now(),phase=args.phase))
    return 1 if any(s['classification'] in ('unexpected_failure','acquisition_failed') for s in summaries) else 0

if __name__=='__main__':
    raise SystemExit(main())
