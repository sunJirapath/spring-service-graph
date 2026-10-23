"""Acquire the frozen 200 source pins without running downloaded build scripts."""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import re
import subprocess

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
OUT=ROOT/'build/full200'
CLONES=OUT/'repos'
TEMP=OUT/'tmp'
PATTERNS='''/*
!/*/
/**/src/main/
/**/pom.xml
/**/build.gradle
/**/build.gradle.kts
/**/settings.gradle
/**/settings.gradle.kts
/**/gradle.properties
/**/gradle/*.toml
/**/*.gradle
/**/*.gradle.kts
/**/*.sql
/**/openapi*.yml
/**/openapi*.yaml
/**/openapi*.json
/**/swagger*.yml
/**/swagger*.yaml
/**/swagger*.json
'''

def prepare():
    for path in (OUT,CLONES,TEMP,OUT/'qualification'):
        path.mkdir(parents=True,exist_ok=True)

def manifest():
    rows=json.loads((HERE/'manifest.json').read_text(encoding='utf-8'))
    assert len(rows)==200 and len({r['repository'].casefold() for r in rows})==200
    for row in rows:
        assert re.fullmatch(r'[\w.-]+/[\w.-]+',row['repository'])
        assert re.fullmatch(r'[0-9a-f]{40}',row['commit'])
        assert row['reference']['source_commit']==row['commit']
        assert row['tool'] in ('maven','gradle')
    return rows

def git(path,*args,input=None):
    env=dict(os.environ,TEMP=str(TEMP),TMP=str(TEMP),TMPDIR=str(TEMP),GIT_TERMINAL_PROMPT='0')
    return subprocess.check_output(['git','-c','core.longpaths=true','-C',str(path),*args],
        input=input,text=True,encoding='utf-8',errors='replace',env=env,timeout=600).strip()

def acquire(row):
    path=CLONES/row['repository'].replace('/','--')
    record=OUT/'qualification'/(path.name+'.json')
    result=dict(row,path=str(path),qualified=False)
    try:
        assert path.resolve().is_relative_to(CLONES.resolve()) and not path.is_symlink()
        if not path.exists():
            path.mkdir()
            git(path,'init','-q')
            git(path,'remote','add','origin','https://github.com/'+row['repository']+'.git')
        try:
            head=git(path,'rev-parse','--verify','--quiet','HEAD')
        except subprocess.CalledProcessError:
            head=''
        if head:
            assert head==row['commit'],'Existing task checkout has a different pin'
        else:
            git(path,'fetch','-q','--depth','1','--filter=blob:none','origin',row['commit'])
            git(path,'sparse-checkout','init','--no-cone')
            git(path,'sparse-checkout','set','--no-cone','--stdin',input=PATTERNS)
            git(path,'checkout','-q','FETCH_HEAD')
        assert git(path,'rev-parse','HEAD')==row['commit'],'Wrong source commit'
        assert not git(path,'status','--porcelain'),'Dirty checkout'
        java=[f for f in git(path,'ls-files','-z').split('\0') if '/src/main/java/' in '/'+f and f.endswith('.java')]
        assert len(java)==row['java_files'],('Tracked Java count differs',len(java),row['java_files'])
        assert all((path/f).is_file() for f in java),'Missing tracked Java source'
        tier='small' if len(java)<50 else 'medium' if len(java)<250 else 'large' if len(java)<1000 else 'huge'
        assert tier==row['tier'],'Source tier differs'
        if row.get('boot_evidence'):
            proof=row['boot_evidence']
            lines=(path/proof['file']).read_text(encoding='utf-8-sig',errors='replace').splitlines()
            start=proof['line']-1
            excerpt='\n'.join(lines[max(0,start-5):start+6])
            assert row['boot_version'] in excerpt,'Pinned Boot evidence differs'
            result['verified_boot_excerpt']=excerpt
        result.update(qualified=True,measured_java_files=len(java),snapshot='sparse source snapshot; no foreign build')
    except Exception as exc:
        result['error']=str(exc)
    record.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print('ACQUIRED',row['repository'],'qualified' if result['qualified'] else 'FAILED '+result['error'],flush=True)
    return result

if __name__=='__main__':
    prepare()
    with ThreadPoolExecutor(max_workers=2) as pool:
        rows=list(pool.map(acquire,manifest()))
    (OUT/'qualified.json').write_text(json.dumps(rows,indent=2)+'\n',encoding='utf-8')
    assert all(r['qualified'] for r in rows)
