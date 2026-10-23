"""Verify the eight audit fixes; --expect baseline checks the original wrong results.

Uses the installed scanner and only Python stdlib. Baseline evidence is archived
separately; do not compare results from different scanner builds as one campaign.
"""
import argparse
import json
import os
import shutil
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = ROOT / 'build/github40-reproductions'
FIXTURES = HERE / 'fixtures'
TEMP = OUT / 'tmp'
TEMP.mkdir(parents=True, exist_ok=True)
os.environ.update(TEMP=str(TEMP), TMP=str(TEMP))
JAVA = ['java', '-Xmx768m', '-Djava.io.tmpdir=' + str(TEMP), '-cp',
        str(ROOT / 'build/install/ssg/lib/*'), 'io.github.sunjirapath.ssg.Main']
results = []
parser = argparse.ArgumentParser()
parser.add_argument('--expect', choices=['fixed', 'baseline'], default='fixed')
fixed = parser.parse_args().expect == 'fixed'

def scan(name, paths):
    output = OUT / name
    p = subprocess.run(JAVA + ['scan', '--repos', *map(str, paths), '--out', str(output)],
                       cwd=ROOT, capture_output=True, text=True, encoding='utf-8', timeout=120)
    output.mkdir(exist_ok=True)
    (output / 'stderr.log').write_text(p.stderr, encoding='utf-8')
    assert p.returncode == 0, (name, p.returncode, p.stderr)
    return json.loads((output / 'graph.json').read_text(encoding='utf-8'))

g = scan('mongo-default', [FIXTURES / 'mongo-default'])
assert any(e['kind'] == 'USES' for e in g['edges']) == fixed
assert any(f['kind'] == 'UNRESOLVED' for f in g['findings']) != fixed
results.append(dict(case='mongo-default', expected='USES customer', fixed=fixed))

g = scan('elasticsearch-index', [FIXTURES / 'elasticsearch-index'])
tables = [n['name'] for n in g['nodes'] if n['kind'] == 'table']
assert ('catalog' in tables and 'product' not in tables) if fixed else ('product' in tables and 'catalog' not in tables), tables
results.append(dict(case='elasticsearch-index', expected='catalog', actual=tables))

g = scan('rabbit-overload', [FIXTURES / 'rabbit-overload'])
topics = [n['name'] for n in g['nodes'] if n['kind'] == 'topic']
assert ('orders.key' in topics and 'payload' not in topics) if fixed else ('payload' in topics and 'orders.key' not in topics), topics
results.append(dict(case='rabbit-overload', expected='orders.key', actual=topics))

g = scan('url-query', [FIXTURES / 'url-query/caller', FIXTURES / 'url-query/target'])
calls = [e for e in g['edges'] if e['kind'] == 'CALLS']
target = 'endpoint:target:GET /' if fixed else 'external:target?x=1'
assert any(e['to'] == target for e in calls), calls
results.append(dict(case='url-query', expected='endpoint:target:GET /', actual=target))

g = scan('openapi-framework-prefix', [FIXTURES / 'openapi-framework-prefix'])
assert any(n['kind'] == 'service' for n in g['nodes'])
assert any(n['kind'] == 'endpoint' and n['path'].endswith('/owners') for n in g['nodes']) == fixed
assert not g['findings']
control = OUT / 'controls/openapi-framework-prefix'
shutil.copytree(FIXTURES / 'openapi-framework-prefix', control, dirs_exist_ok=True)
controller = next(control.rglob('OwnerRestControllerV1.java'))
controller.write_text(controller.read_text().replace('import org.springframework.samples.petclinic.rest.api.OwnersApi;',
                                                   'import com.example.api.OwnersApi;'))
control_graph = scan('openapi-control', [control])
assert any(n['kind'] == 'endpoint' and n['path'].endswith('/owners') for n in control_graph['nodes'])
results.append(dict(case='openapi-framework-prefix', expected='GET /petclinic/api/owners', fixed=fixed,
                    control='The com.example.api import also yields the endpoint'))

g = scan('feign-package', [FIXTURES / 'feign-package'])
assert any(n['kind'] == 'endpoint' for n in g['nodes'])
assert any(e['kind'] == 'CALLS' and e['to'] == 'endpoint:users:GET /users/{id}' for e in g['edges']) == fixed
results.append(dict(case='feign-package', expected='CALLS users GET /users/{id}', fixed=fixed))

for rev in ['base', 'head']:
    scan('retirement-' + rev, [FIXTURES / 'retirement' / rev / service for service in ['caller', 'orders']])
p = subprocess.run(JAVA + ['diff', '--base', str(OUT / 'retirement-base'), '--head', str(OUT / 'retirement-head'),
                           '--fail-on-breaking'], capture_output=True, text=True, encoding='utf-8', timeout=60)
assert (p.returncode == 0 and 'is still called by caller' not in p.stdout) if fixed else (p.returncode == 3 and 'is still called by caller' in p.stdout), (p.returncode, p.stdout, p.stderr)
(OUT / 'retirement-diff.md').write_text(p.stdout, encoding='utf-8')
results.append(dict(case='coordinated-retirement', expected='exit 0', actual_exit=p.returncode))

roots = [FIXTURES / 'duplicate-evidence' / name for name in ['alpha', 'beta']]
g = scan('duplicate-evidence', roots)
finding = next(f for f in g['findings'] if f['kind'] == 'DUPLICATE_SERVICE')
evidence = finding['evidence'][0]
assert all((next(r for r in roots if r.name == e['repo']) / e['file']).is_file() for e in finding['evidence']) if fixed else (roots[1] / evidence['file']).is_dir(), evidence
results.append(dict(case='duplicate-service-evidence', expected='Configuration or launcher source file and line',
                    fixed=fixed))

(OUT / 'repro-results.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
print(json.dumps(dict(mode='fixed' if fixed else 'baseline', passed=len(results), results=results), indent=2))
