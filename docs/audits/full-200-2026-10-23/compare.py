"""Compare each graph with the most recent retained graph at the identical source pin."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import zipfile

ROOT=Path(__file__).resolve().parents[3]

def key(value):
    return json.dumps(value,sort_keys=True,ensure_ascii=False,separators=(',',':'))

def compare(row,graph_file,out):
    reference=row['reference']
    archive_path=(ROOT/reference['archive']).resolve()
    assert archive_path.is_relative_to((ROOT/'docs/audits').resolve())
    with zipfile.ZipFile(archive_path) as archive:
        data=archive.read(reference['entry'])
    assert hashlib.sha256(data).hexdigest()==reference['graph_sha256']
    before=json.loads(data)
    after=json.loads(graph_file.read_text(encoding='utf-8'))
    changes={}
    for kind in ('nodes','edges','findings'):
        old={key(v):v for v in before[kind]};new={key(v):v for v in after[kind]}
        added=[new[k] for k in sorted(new.keys()-old.keys())]
        removed=[old[k] for k in sorted(old.keys()-new.keys())]
        if added or removed:
            changes[kind]=dict(added=added,removed=removed,
                added_kinds=dict(Counter(v['kind'] for v in added)),removed_kinds=dict(Counter(v['kind'] for v in removed)))
    result=dict(repository=row['repository'],commit=row['commit'],reference=reference,
        current_graph_sha256=hashlib.sha256(graph_file.read_bytes()).hexdigest(),
        semantic_change=before!=after,changes=changes,
        before_counts={k:len(before[k]) for k in ('nodes','edges','findings')},
        after_counts={k:len(after[k]) for k in ('nodes','edges','findings')})
    out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    return result
