"""Compare the complete final campaign with the complete before-build graph archive."""
import copy
import hashlib
import json
from pathlib import Path
import zipfile

HERE=Path(__file__).resolve().parent
OUT=HERE/'evidence'

def key(value):
    return json.dumps(value,sort_keys=True,ensure_ascii=False,separators=(',',':'))

def compare():
    rows=json.loads((HERE/'manifest.json').read_text(encoding='utf-8'))
    summaries=[]
    with zipfile.ZipFile(OUT/'before-graphs.zip') as before,zipfile.ZipFile(OUT/'after-graphs.zip') as after:
        assert len(before.namelist())==len(after.namelist())==len(rows)==200
        for row in rows:
            entry=row['repository'].replace('/','--')+'/graph.json'
            old_data=before.read(entry);new_data=after.read(entry)
            old=json.loads(old_data);new=json.loads(new_data)
            changes={}
            for kind in ('nodes','edges','findings'):
                old_set={key(v):v for v in old[kind]};new_set={key(v):v for v in new[kind]}
                added=[new_set[k] for k in sorted(new_set.keys()-old_set.keys())]
                removed=[old_set[k] for k in sorted(old_set.keys()-new_set.keys())]
                if added or removed:changes[kind]=dict(added=added,removed=removed)
            lexical_only=False
            if row['repository']=='shuigedeng/taotao-cloud-project':
                expected=copy.deepcopy(old)
                matched=[f for f in expected['findings'] if f['kind']=='PARSE_ERROR'
                    and f['message'].startswith('Lexical error at line 155, column ')]
                assert len(matched)==1 and matched[0]['evidence'][0]['line']==1
                matched[0]['evidence'][0]['line']=155
                lexical_only=expected==new
            summaries.append(dict(repository=row['repository'],commit=row['commit'],
                byte_identical=old_data==new_data,json_identical=old==new,changes=changes,
                before_graph_sha256=hashlib.sha256(old_data).hexdigest(),
                after_graph_sha256=hashlib.sha256(new_data).hexdigest(),
                expected_lexical_evidence_correction_only=lexical_only))
    result=dict(repositories=200,byte_identical=sum(s['byte_identical'] for s in summaries),
        json_identical=sum(s['json_identical'] for s in summaries),
        changed=[s for s in summaries if not s['json_identical']],
        unexpected_changes=[s['repository'] for s in summaries if not s['json_identical']
            and not s['expected_lexical_evidence_correction_only']],results=summaries)
    (OUT/'phase-comparison.json').write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k not in ('changed','results')},indent=2))
    return result

if __name__=='__main__':compare()
