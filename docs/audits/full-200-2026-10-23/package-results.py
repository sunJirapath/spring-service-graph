"""Retain a completed 200-repository campaign and verify graph/output provenance."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import zipfile

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
OUT=ROOT/'build/full200'
EVIDENCE=HERE/'evidence'

def sha(data):
    return hashlib.sha256(data).hexdigest()

def read(path):
    return json.loads(path.read_text(encoding='utf-8'))

def write(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')

def sanitize(value):
    if isinstance(value,dict):
        return {k:sanitize(v) for k,v in value.items() if k not in ('stdout_tail','stderr_tail')}
    if isinstance(value,list):return [sanitize(v) for v in value]
    if isinstance(value,str):
        for base in (str(ROOT),str(ROOT).replace('\\','/')):
            value=value.replace(base+'\\','').replace(base+'/','')
        return value
    return value

def package(run_id,label):
    run=OUT/'runs'/run_id
    assert run.is_dir() and run.resolve().is_relative_to((OUT/'runs').resolve())
    campaign=read(run/'campaign.json')
    manifest=read(HERE/'manifest.json')
    assert campaign['completed']==campaign['expected_repositories']==len(manifest)==200
    pins={r['repository']:r['commit'] for r in manifest}
    assert {r['repository']:r['commit'] for r in campaign['results']}==pins
    assert Counter(r['classification'] for r in campaign['results'])=={'passed':195,'expected_unsupported':5}
    results=[];hashes={};deltas=[];totals=Counter();nodes=Counter();edges=Counter();findings=Counter()
    EVIDENCE.mkdir(parents=True,exist_ok=True)
    graphs_path=EVIDENCE/(label+'-graphs.zip')
    changes_path=EVIDENCE/(label+'-deltas.zip')
    with zipfile.ZipFile(graphs_path,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as graphs, \
            zipfile.ZipFile(changes_path,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as changes:
        for summary in campaign['results']:
            slug=summary['repository'].replace('/','--')
            result=read(run/'results'/(slug+'.json'))
            assert result['commit']==pins[summary['repository']]
            assert result['scanner_jar_sha256']==campaign['scanner_jar_sha256']
            assert result['distribution_sha256']==campaign['distribution_sha256']
            per_run={}
            for number in (1,2):
                folder=run/f'run{number}'/slug
                actual={p.relative_to(folder).as_posix():sha(p.read_bytes()) for p in sorted(folder.rglob('*')) if p.is_file()}
                assert actual==result['output_file_sha256'],(slug,number,'output hashes changed')
                per_run[f'run{number}']=actual
            assert result['deterministic_output'] and len(result['scans'])==2
            assert all(s['exit_code']==0 for s in result['scans'])
            assert result['diff_identical']['status']=='passed'
            graph_data=(run/'run1'/slug/'graph.json').read_bytes()
            graph=json.loads(graph_data)
            graphs.writestr(slug+'/graph.json',graph_data)
            delta=read(run/'changes'/(slug+'.json'))
            assert delta['current_graph_sha256']==sha(graph_data)
            assert delta['commit']==pins[summary['repository']]
            assert delta['semantic_change']==summary['semantic_change']
            changes.writestr(slug+'.json',json.dumps(sanitize(delta),indent=2,ensure_ascii=False)+'\n')
            deltas.append(dict(repository=result['repository'],semantic_change=delta['semantic_change'],
                before_counts=delta['before_counts'],after_counts=delta['after_counts'],
                changes={k:{'added':len(v['added']),'removed':len(v['removed']),
                    'added_kinds':v['added_kinds'],'removed_kinds':v['removed_kinds']} for k,v in delta['changes'].items()}))
            result['classification']=summary['classification']
            results.append(sanitize(result));hashes[slug]=per_run
            totals['source_scans']+=2;totals['repeat_output_checks']+=1;totals['identical_diffs']+=1
            totals['output_files_per_run']+=len(result['output_file_sha256'])
            totals['evidence_locations_checked']+=result['graph_validation']['evidence_locations_checked']
            if result.get('mcp'):
                assert result['mcp']['status']=='passed'
                totals['mcp_checks']+=1
                totals['mcp_incoming_edges_verified']+=result['mcp']['incoming_edges_verified']
                totals['mcp_impact_rows_verified']+=result['mcp']['impact_rows_verified']
            nodes.update(n['kind'] for n in graph['nodes'])
            edges.update(e['kind'] for e in graph['edges'])
            findings.update(f['kind'] for f in graph['findings'])
    metadata={k:v for k,v in campaign.items() if k!='results'}
    metadata.update(classifications=dict(Counter(r['classification'] for r in campaign['results'])),
        totals=dict(totals),node_kinds=dict(nodes),edge_kinds=dict(edges),finding_kinds=dict(findings),
        changed_graphs=sum(d['semantic_change'] for d in deltas),
        graph_archive=dict(path=graphs_path.relative_to(ROOT).as_posix(),entries=200,sha256=sha(graphs_path.read_bytes())),
        delta_archive=dict(path=changes_path.relative_to(ROOT).as_posix(),entries=200,sha256=sha(changes_path.read_bytes())),
        source_only=True,foreign_builds_run=0)
    write(EVIDENCE/(label+'-metadata.json'),sanitize(metadata))
    write(EVIDENCE/(label+'-results.json'),results)
    write(EVIDENCE/(label+'-output-sha256.json'),hashes)
    write(EVIDENCE/(label+'-changes.json'),deltas)
    print(json.dumps(metadata,indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run',required=True)
    parser.add_argument('--label',choices=('before','after'),required=True)
    args=parser.parse_args()
    assert len(args.run)==16 and all(c in '0123456789abcdef' for c in args.run)
    package(args.run,args.label)
