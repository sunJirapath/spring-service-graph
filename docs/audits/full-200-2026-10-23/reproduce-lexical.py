"""Verify lexical parse-error evidence using the installed scanner, without a foreign build."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[3]
OUT=ROOT/'build/full200/lexical'
TEMP=OUT/'tmp'
SOURCE=OUT/'repo/src/main/java/app'
for folder in (TEMP,SOURCE):folder.mkdir(parents=True,exist_ok=True)
(SOURCE/'App.java').write_text('package app; @SpringBootApplication class App {}\n',encoding='utf-8')
(SOURCE/'Broken.java').write_text('package app;\nclass Broken {\n\n\nString text = "\\:";\n}\n',encoding='utf-8')
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--expect',choices=('baseline','fixed'),required=True)
mode=parser.parse_args().expect
env=dict(os.environ,TEMP=str(TEMP),TMP=str(TEMP),TMPDIR=str(TEMP))
process=subprocess.run(['java','-Xmx768m','-Djava.io.tmpdir='+str(TEMP),'-cp',
    str(ROOT/'build/install/ssg/lib/*'),'io.github.sunjirapath.ssg.Main','scan','--repos',
    str(OUT/'repo'),'--out',str(OUT/'graph')],capture_output=True,text=True,encoding='utf-8',env=env,timeout=60)
assert process.returncode==0,process.stderr
graph_file=OUT/'graph/graph.json'
graph=json.loads(graph_file.read_text(encoding='utf-8'))
findings=[f for f in graph['findings'] if f['kind']=='PARSE_ERROR']
assert len(findings)==1 and findings[0]['message'].startswith('Lexical error at line 5, column ')
line=findings[0]['evidence'][0]['line']
assert line==(5 if mode=='fixed' else 1),line
jar=next((ROOT/'build/install/ssg/lib').glob('spring-service-graph-*.jar'))
result=dict(mode=mode,passed=True,source_error_line=5,evidence_line=line,
    retained_parse_errors=1,scanner_jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
    graph_sha256=hashlib.sha256(graph_file.read_bytes()).hexdigest())
(OUT/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result))
