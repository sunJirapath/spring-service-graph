"""Reproduce composed EnableFeignClients package alias handling.

The fixtures are invented multi-module projects written under build/full200/feign.
Run with the installed scanner distribution and select the expected behavior:

    python docs/audits/full-200-2026-10-23/reproduce-feign.py --expect baseline
    python docs/audits/full-200-2026-10-23/reproduce-feign.py --expect fixed
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = ROOT / "build/full200/feign"
FIXTURES = OUT / "fixtures"
GRAPHS = OUT / "graphs"
TEMP = OUT / "tmp"
for path in (FIXTURES, GRAPHS, TEMP):
    path.mkdir(parents=True, exist_ok=True)
os.environ.update(TEMP=str(TEMP), TMP=str(TEMP))
JAVA = [
    "java", "-Xmx768m", "-Djava.io.tmpdir=" + str(TEMP), "-cp",
    str(ROOT / "build/install/ssg/lib/*"), "io.github.sunjirapath.ssg.Main",
]


def file(root, relative, content):
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")


def fixture(name, usage, meta, members, retention="@Retention(RetentionPolicy.RUNTIME)"):
    root = FIXTURES / name
    file(root, "orders/pom.xml", """<project><groupId>demo</groupId><artifactId>orders</artifactId>
      <dependencies><dependency><groupId>demo</groupId><artifactId>shared</artifactId></dependency></dependencies></project>""")
    file(root, "shared/pom.xml", "<project><groupId>demo</groupId><artifactId>shared</artifactId></project>")
    file(root, "orders/src/main/java/app/Orders.java",
         "package app; @SpringBootApplication " + usage + " class Orders {}\n")
    file(root, "orders/src/main/java/app/RemoteClients.java", """package app;
        import java.lang.annotation.*;
        """ + retention + "\n" + meta + "\n@interface RemoteClients {\n" + members + "\n}\n")
    file(root, "users/src/main/java/users/Users.java", """package users;
        @SpringBootApplication @RestController class Users {
            @GetMapping("/users/{id}") Object get() { return null; }
        }
        """)
    file(root, "shared/src/main/java/clients/UsersApi.java", """package clients;
        @FeignClient(name="users")
        public interface UsersApi { @GetMapping("/users/{id}") Object get(); }
        """)
    return root


PAIRED = """    @AliasFor(annotation=EnableFeignClients.class, attribute="value")
    String[] value() default {};
    @AliasFor(annotation=EnableFeignClients.class, attribute="basePackages")
    String[] basePackages() default {};"""

cases = [
    ("paired-value-only", "@RemoteClients(value = \"clients\")", "@EnableFeignClients", PAIRED, True, False,
     "@Retention(RetentionPolicy.RUNTIME)"),
    ("paired-basepackages-only", "@RemoteClients(basePackages = \"clients\")", "@EnableFeignClients", PAIRED, True, True,
     "@Retention(RetentionPolicy.RUNTIME)"),
    ("independent-meta-value-and-basepackages-override",
     "@RemoteClients(basePackages = \"unused\")", "@EnableFeignClients(\"clients\")",
     """    @AliasFor(annotation=EnableFeignClients.class, attribute="basePackages")
    String[] basePackages() default {"unused"};""", True, False, "@Retention(RetentionPolicy.RUNTIME)"),
    ("retention-default-class", "@RemoteClients(basePackages = \"clients\")", "@EnableFeignClients",
     """    @AliasFor(annotation=EnableFeignClients.class, attribute="basePackages")
    String[] basePackages() default {};""", False, True, ""),
    ("retention-class", "@RemoteClients(basePackages = \"clients\")", "@EnableFeignClients",
     """    @AliasFor(annotation=EnableFeignClients.class, attribute="basePackages")
    String[] basePackages() default {};""", False, True, "@Retention(RetentionPolicy.CLASS)"),
    ("retention-source", "@RemoteClients(basePackages = \"clients\")", "@EnableFeignClients",
     """    @AliasFor(annotation=EnableFeignClients.class, attribute="basePackages")
    String[] basePackages() default {};""", False, True, "@Retention(RetentionPolicy.SOURCE)"),
    ("retention-runtime", "@RemoteClients(basePackages = \"clients\")", "@EnableFeignClients",
     """    @AliasFor(annotation=EnableFeignClients.class, attribute="basePackages")
    String[] basePackages() default {};""", True, True, "@Retention(RetentionPolicy.RUNTIME)"),
]

parser = argparse.ArgumentParser()
parser.add_argument("--expect", choices=("baseline", "fixed"), required=True)
mode = parser.parse_args().expect
fixed = mode == "fixed"
jar_dir = ROOT / "build/install/ssg/lib"
assert jar_dir.is_dir(), f"installed scanner distribution is missing: {jar_dir}"
jar_hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
              for p in sorted(jar_dir.glob("*.jar"))}
assert jar_hashes, f"no scanner jars found in {jar_dir}"
results = []

for name, usage, meta, members, expected_fixed, expected_baseline, retention in cases:
    root = fixture(name, usage, meta, members, retention)
    graph_dir = GRAPHS / name
    process = subprocess.run(
        JAVA + ["scan", "--repos", str(root), "--out", str(graph_dir)],
        cwd=ROOT, capture_output=True, text=True, encoding="utf-8", timeout=120,
    )
    (graph_dir / "stderr.log").parent.mkdir(parents=True, exist_ok=True)
    (graph_dir / "stderr.log").write_text(process.stderr, encoding="utf-8")
    assert process.returncode == 0, (name, process.returncode, process.stdout, process.stderr)
    graph = json.loads((graph_dir / "graph.json").read_text(encoding="utf-8"))
    calls = [edge for edge in graph["edges"] if edge["kind"] == "CALLS"]
    destinations = sorted(edge["to"] for edge in calls)
    has_users_call = "endpoint:users:GET /users/{id}" in destinations
    expected = expected_fixed if fixed else expected_baseline
    assert has_users_call == expected, (name, mode, destinations, graph["findings"])
    results.append({
        "case": name,
        "mode": mode,
        "expected_users_call": expected,
        "actual_call_destinations": destinations,
        "graph": str(graph_dir.relative_to(ROOT)).replace("\\", "/") + "/graph.json",
        "passed": True,
    })

result = {
    "mode": mode,
    "scanner_jars_sha256": jar_hashes,
    "cases_passed": len(results),
    "results": results,
}
result_path = OUT / "repro-results.json"
result_path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result, indent=2))
