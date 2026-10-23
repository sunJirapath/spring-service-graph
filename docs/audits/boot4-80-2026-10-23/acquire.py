"""Acquire source-only sparse checkouts and independently verify corpus metadata."""
import concurrent.futures as futures
import json
import os
from pathlib import Path
import re
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = ROOT / 'build/fix-audit'
CLONES = ROOT / 'bench/boot4-audit'
TEMP = OUT / 'tmp'
TEMP.mkdir(parents=True, exist_ok=True)
CLONES.mkdir(parents=True, exist_ok=True)
ENV = dict(os.environ, TEMP=str(TEMP), TMP=str(TEMP), GIT_TERMINAL_PROMPT='0')

def git(path, *args, input=None):
    return subprocess.check_output(['git','-c','core.longpaths=true','-C',str(path),*args],
            input=input, text=True,encoding='utf-8',errors='replace',env=ENV,timeout=600).strip()

def select():
    rows=json.loads((HERE/'manifest.json').read_text(encoding='utf-8'))
    assert len(rows)==80 and len({r['repository'].casefold() for r in rows})==80
    for tool in ['maven','gradle']:
        for tier in ['small','medium','large','huge']:
            assert sum(r['build_system']==tool and r['tier']==tier for r in rows)==10
    for row in rows:
        row['tool']=row['build_system']
        row['version_file']=row['boot_evidence']['file']
        row['version_line']=row['boot_evidence']['line']
        assert len(row['commit'])==40 and int(row['boot_version'].split('.')[0])>=4
    return rows


def acquire(row):
    slug=row['repository'].replace('/','--')
    path=CLONES/slug
    dest=OUT/'qualification'/ (slug+'.json')
    if dest.exists():
        prior=json.loads(dest.read_text())
        if prior.get('commit')==row['commit'] and prior.get('qualified') and path.exists():
            return prior
    try:
        if not path.exists():
            path.mkdir()
            git(path,'init','-q')
            git(path,'remote','add','origin','https://github.com/'+row['repository']+'.git')
        git(path,'fetch','-q','--depth','1','--filter=blob:none','origin',row['commit'])
        git(path,'sparse-checkout','init','--no-cone')
        patterns='''/*
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
        git(path,'sparse-checkout','set','--no-cone','--stdin',input=patterns)
        git(path,'checkout','-q','FETCH_HEAD')
        assert git(path,'rev-parse','HEAD')==row['commit'],'wrong commit'
        assert not git(path,'status','--porcelain'),'dirty checkout'
        files=git(path,'ls-files','-z').split('\0')
        java=[f for f in files if '/src/main/java/' in '/'+f and f.endswith('.java')]
        missing=[f for f in java if not (path/f).is_file()]
        assert not missing,('missing production Java',missing[:3])
        n=len(java)
        tier='small' if n<50 else 'medium' if n<250 else 'large' if n<1000 else 'huge'
        assert n==row['java_files'] and tier==row['tier'],('source count mismatch',n,row['java_files'])
        version_file=path/row['version_file']
        text=version_file.read_text(encoding='utf-8-sig',errors='replace')
        assert row['boot_version'] in text,('version not in evidence file',row['version_file'])
        lines=text.splitlines()
        at=row['version_line']-1
        window='\n'.join(lines[max(0,at-5):at+6])
        assert re.search(r'spring.?boot|springframework.boot|bootVersion',text,re.I), 'no Spring Boot build declaration'
        assert row['boot_version'] in window,('version not at declared line',row['version_line'])
        app_files=[]
        for f in java:
            content=(path/f).read_text(encoding='utf-8-sig',errors='replace')
            if re.search(r'@SpringBootApplication\b|SpringApplication(?:Builder)?\b',content): app_files.append(f)
        assert app_files,'no source app/launcher marker'
        result=dict(row,path=str(path),measured_java_files=n,app_files=app_files,qualified=True,
                    version_evidence_excerpt=window,snapshot='sparse: all src/main, build descriptors, SQL and API specs; no foreign build')
    except Exception as exc:
        result=dict(row,path=str(path),qualified=False,error=str(exc))
    dest.write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps({k:result.get(k) for k in ['repository','tool','tier','boot_version','qualified','error']}),flush=True)
    return result

if __name__=='__main__':
    (OUT/'qualification').mkdir(exist_ok=True)
    rows=select()
    print('Selected',len(rows),'distinct repos',flush=True)
    with futures.ThreadPoolExecutor(max_workers=2) as pool:
        result=list(pool.map(acquire,rows))
    (OUT/'qualified.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print('Qualified',sum(r['qualified'] for r in result),'of',len(result),flush=True)
