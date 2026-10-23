"""Repeat source-only post-fix validation over the pinned qualified Boot 4 corpus.

Reads build/fix-audit/qualified.json and its already acquired sparse checkouts.
Does not build foreign repositories, fetch sources, or install the scanner. JVMs
are serialized with a 768 MB heap; all child temp files stay under build/fix-audit.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parents[2]
OUT = ROOT / "build/fix-audit"
QUALIFIED = OUT / "qualified.json"
CLONES = ROOT / "bench/boot4-audit"
SUITE = "boot4-post-fix-80"
EXPECTED_COUNT = 80
EXPECTED_PER_GROUP = 10
REGISTRY = ROOT / ".local-audits/registry.json"
TEMP = OUT / "tmp"
LIB = ROOT / "build/install/ssg/lib"
JAVA_CLASS = "io.github.sunjirapath.ssg.Main"
TIERS = ("small", "medium", "large", "huge")
TOOLS = ("who_calls", "impact_of", "service_card")


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def json_write_atomic(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + f".{os.getpid()}.tmp")
    temp.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    temp.replace(path)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def installed_scanner():
    jars = sorted(LIB.glob("*.jar"))
    if not jars:
        raise RuntimeError(f"No installed distribution jars found under {LIB}; run installDist before this runner.")
    manifest = hashlib.sha256()
    for jar in jars:
        manifest.update(jar.name.encode("utf-8"))
        manifest.update(b"\0")
        manifest.update(bytes.fromhex(sha256(jar)))
        manifest.update(b"\n")
    distribution_hash = manifest.hexdigest()
    app_jars = [jar for jar in jars if jar.name.startswith("spring-service-graph-") and jar.name.endswith(".jar")]
    if len(app_jars) != 1:
        raise RuntimeError(f"Expected one ssg application jar under {LIB}, found {len(app_jars)}")
    app_jar_hash = sha256(app_jars[0])
    commit = git(ROOT, "rev-parse", "HEAD")
    branch = git(ROOT, "branch", "--show-current")
    dirty = bool(git(ROOT, "status", "--porcelain"))
    source_state = f"branch:{branch};working_tree_dirty:{str(dirty).lower()}"
    return app_jar_hash, distribution_hash, commit, source_state, [str(jar) for jar in jars]


def git(cwd, *args):
    env = child_env()
    return subprocess.check_output(["git", "-c", "core.longpaths=true", "-C", str(cwd), *args],
                                   text=True, encoding="utf-8", errors="replace", env=env,
                                   timeout=120).strip()


def child_env():
    TEMP.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ)
    env.update(TEMP=str(TEMP), TMP=str(TEMP), TMPDIR=str(TEMP), GIT_TERMINAL_PROMPT="0")
    return env


def java_command():
    classpath = str(LIB / "*")
    return ["java", "-Xmx768m", "-Djava.io.tmpdir=" + str(TEMP), "-cp", classpath, JAVA_CLASS]


def file_hashes(folder):
    return {
        p.relative_to(folder).as_posix(): sha256(p)
        for p in sorted(folder.rglob("*"))
        if p.is_file()
    }


def tree_digest(hashes):
    digest = hashlib.sha256()
    for name, value in sorted(hashes.items()):
        digest.update(name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(value.encode("ascii"))
        digest.update(b"\n")
    return digest.hexdigest()


def safe_clear_output(folder, parent):
    resolved_parent = parent.resolve()
    resolved = folder.resolve()
    if resolved == resolved_parent or not resolved.is_relative_to(resolved_parent):
        raise RuntimeError(f"Refusing to clear output outside run directory: {folder}")
    if folder.is_symlink():
        raise RuntimeError(f"Refusing to clear symlink output: {folder}")
    if folder.exists():
        shutil.rmtree(folder)


def source_checkout(row):
    raw = Path(row["path"])
    checkout = raw if raw.is_absolute() else ROOT / raw
    checkout = checkout.resolve()
    allowed = CLONES.resolve()
    if not checkout.is_relative_to(allowed):
        raise RuntimeError(f"Qualified checkout is outside {allowed}: {checkout}")
    if not checkout.is_dir():
        raise RuntimeError(f"Qualified checkout is missing: {checkout}")
    actual = git(checkout, "rev-parse", "HEAD")
    if actual != row["commit"]:
        raise RuntimeError(f"Wrong checkout commit for {row['repository']}: {actual} != {row['commit']}")
    if git(checkout, "status", "--porcelain"):
        raise RuntimeError(f"Qualified checkout is dirty: {checkout}")
    return checkout


def load_candidates(manifest):
    if not manifest.is_file():
        raise RuntimeError(f"Missing qualification manifest: {manifest}")
    rows = json.loads(manifest.read_text(encoding="utf-8"))
    if not isinstance(rows, list):
        raise RuntimeError("qualified.json must be a JSON array")
    if len(rows) != EXPECTED_COUNT:
        raise RuntimeError(f"Expected {EXPECTED_COUNT} qualified repositories, found {len(rows)}")
    names = [r.get("repository", "").casefold() for r in rows]
    if len(set(names)) != EXPECTED_COUNT:
        raise RuntimeError("qualified.json must contain distinct repositories")
    counts = Counter((r.get("tool"), r.get("tier")) for r in rows)
    for tool in ("maven", "gradle"):
        if sum(r.get("tool") == tool for r in rows) != EXPECTED_COUNT // 2:
            raise RuntimeError(f"Expected equal Maven and Gradle counts: {tool}")
        if EXPECTED_PER_GROUP is not None:
            for tier in TIERS:
                if counts[(tool, tier)] != EXPECTED_PER_GROUP:
                    raise RuntimeError(f"Expected {EXPECTED_PER_GROUP} {tool} {tier} candidates, found {counts[(tool, tier)]}")
    for row in rows:
        if not row.get("qualified"):
            raise RuntimeError(f"Unqualified candidate in final manifest: {row.get('repository')}")
        if row.get("tool") not in ("maven", "gradle") or row.get("tier") not in TIERS:
            raise RuntimeError(f"Invalid tool/tier in candidate: {row.get('repository')}")
        if len(row.get("commit", "")) != 40:
            raise RuntimeError(f"Missing full source SHA for {row.get('repository')}")
        if int(row.get("boot_version", "0").split(".")[0]) < 4:
            raise RuntimeError(f"Spring Boot 4+ required: {row.get('repository')}")
        if not row.get("path"):
            raise RuntimeError(f"Missing qualified checkout path for {row.get('repository')}")
    return rows


def process_output_evidence(graph, checkout):
    errors = []
    evidence_count = 0
    line_counts = {}
    node_ids = [n.get("id") for n in graph["nodes"]]
    ids = set(node_ids)
    if len(ids) != len(node_ids) or any(not value for value in node_ids):
        errors.append("graph node IDs are missing or duplicated")
    services = [n for n in graph["nodes"] if n.get("kind") == "service"]
    service_names = {n.get("name") for n in services}
    if not services:
        errors.append("scan returned zero services")
    for service in services:
        if service.get("repo") != checkout.name:
            errors.append(f"service repo mismatch: {service.get('repo')} != {checkout.name}")
        service_path = service.get("path")
        if not isinstance(service_path, str) or not service_path:
            errors.append(f"service has no module path: {service.get('name')}")
            continue
        module_path = checkout if service_path == "." else (checkout / Path(*PurePosixPath(service_path).parts)).resolve()
        if not module_path.is_relative_to(checkout.resolve()) or not module_path.is_dir():
            errors.append(f"service module path is not a directory under checkout: {service_path}")
    for edge in graph["edges"]:
        if edge.get("from") not in ids or edge.get("to") not in ids:
            errors.append(f"edge references missing node: {edge.get('from')} -> {edge.get('to')}")
        evidence = edge.get("evidence")
        if not isinstance(evidence, list) or not evidence:
            errors.append(f"edge has no source evidence: {edge.get('kind')} {edge.get('from')} -> {edge.get('to')}")
            continue
        for item in evidence:
            evidence_count += 1
            errors.extend(validate_evidence(item, checkout, line_counts))
    for finding in graph["findings"]:
        finding_service = finding.get("service")
        # Parse problems outside an app module are deliberately owned by the repository.
        repo_parse_problem = finding.get("kind") == "PARSE_ERROR" and finding_service == checkout.name
        if finding_service and finding_service not in service_names and not repo_parse_problem:
            errors.append(f"finding references missing service: {finding.get('kind')} {finding_service}")
        evidence = finding.get("evidence")
        if not isinstance(evidence, list) or not evidence:
            errors.append(f"finding has no source evidence: {finding.get('kind')} {finding.get('service')}")
            continue
        for item in evidence:
            evidence_count += 1
            errors.extend(validate_evidence(item, checkout, line_counts))
    return {
        "valid": not errors,
        "errors": errors,
        "services": len(services),
        "endpoints": sum(n.get("kind") == "endpoint" for n in graph["nodes"]),
        "nodes": len(graph["nodes"]),
        "edges": len(graph["edges"]),
        "findings": len(graph["findings"]),
        "evidence_locations_checked": evidence_count,
        "source_files_line_counted": len(line_counts),
    }


def validate_evidence(item, checkout, line_counts):
    errors = []
    if not isinstance(item, dict):
        return ["source evidence entry is not an object"]
    file_name = item.get("file")
    line = item.get("line")
    expected_repo = checkout.name
    if item.get("repo") != expected_repo:
        errors.append(f"source evidence repo mismatch: {item.get('repo')} != {expected_repo}")
    if not isinstance(file_name, str) or not file_name:
        return ["source evidence has no file path"]
    posix = PurePosixPath(file_name)
    relative = Path(*posix.parts)
    if posix.is_absolute() or relative.is_absolute():
        return [f"source evidence path is absolute: {file_name}"]
    target = (checkout / relative).resolve()
    if not target.is_relative_to(checkout.resolve()):
        return [f"source evidence escapes checkout: {file_name}"]
    if not target.is_file():
        return [f"source evidence is not a regular file: {file_name}"]
    if not isinstance(line, int) or line < 0:
        errors.append(f"source evidence has invalid line: {file_name}:{line}")
    elif line > 0:
        try:
            key = target.as_posix()
            if key not in line_counts:
                with target.open("r", encoding="utf-8-sig", errors="replace") as stream:
                    line_counts[key] = sum(1 for _ in stream)
            count = line_counts[key]
            if line > count:
                errors.append(f"source evidence line exceeds file: {file_name}:{line} > {count}")
        except OSError as exc:
            errors.append(f"cannot read source evidence {file_name}: {exc}")
    return errors


def graph_from(folder):
    path = folder / "graph.json"
    if not path.is_file():
        raise RuntimeError(f"scan did not write graph.json: {path}")
    graph = json.loads(path.read_text(encoding="utf-8"))
    if not all(isinstance(graph.get(k), list) for k in ("nodes", "edges", "findings")):
        raise RuntimeError(f"graph.json has invalid top-level shape: {path}")
    return graph


def graph_targets(graph):
    nodes = {n["id"]: n for n in graph["nodes"]}
    service = next(n for n in graph["nodes"] if n.get("kind") == "service")
    endpoints = [n for n in graph["nodes"] if n.get("kind") == "endpoint"]

    def expand(node):
        if node.get("kind") != "service":
            return [node["id"]]
        return [node["id"]] + [
            n["id"] for n in endpoints if n.get("service") == node.get("name")
        ]

    choices = endpoints if endpoints else [service]
    target = max(choices, key=lambda n: sum(
        e.get("kind") != "EXPOSES" and e.get("to") == n["id"] for e in graph["edges"]
    ))
    target_ids = expand(target)
    return nodes, target, target_ids, service


def edge_key(edge):
    return (edge.get("kind"), edge.get("from"), edge.get("to"))


def expected_incoming(graph, target_ids):
    targets = set(target_ids)
    return [edge_key(e) for e in graph["edges"]
            if e.get("kind") != "EXPOSES" and e.get("to") in targets]


def expected_impact_depth_one(graph, target_ids):
    frontier = set(target_ids)
    seen = set(target_ids)
    expected = []
    for edge in graph["edges"]:
        if edge.get("kind") == "EXPOSES" or edge.get("to") not in frontier or edge.get("from") in seen:
            continue
        seen.add(edge["from"])
        expected.append((edge["from"], edge_key(edge)))
    return expected


def request(method, ident, params):
    return json.dumps({"jsonrpc": "2.0", "id": ident, "method": method, "params": params},
                      separators=(",", ":"))


def mcp_smoke(graph, output, log_path):
    nodes, target, target_ids, service = graph_targets(graph)
    target_id = target["id"]
    messages = [
        request("initialize", 1, {
            "protocolVersion": "2025-03-26",
            "capabilities": {},
            "clientInfo": {"name": "fix-audit-runner", "version": "1"},
        }),
        request("tools/list", 2, {}),
        request("tools/call", 3, {"name": "who_calls", "arguments": {"target": target_id}}),
        request("tools/call", 4, {"name": "impact_of", "arguments": {"target": target_id, "depth": 1}}),
        request("tools/call", 5, {"name": "service_card", "arguments": {"service": service["name"]}}),
    ]
    started_at = utc_now()
    started = time.monotonic()
    try:
        proc = subprocess.run(
            java_command() + ["mcp", "--graph", str(output)],
            input="\n".join(messages) + "\n", capture_output=True, text=True,
            encoding="utf-8", errors="replace", env=child_env(), timeout=90,
        )
        seconds = round(time.monotonic() - started, 3)
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_text(proc.stderr, encoding="utf-8")
        lines = [line for line in proc.stdout.splitlines() if line.strip()]
        if proc.returncode != 0:
            raise RuntimeError(f"MCP exit {proc.returncode}: {proc.stderr[-2000:]}")
        replies = [json.loads(line) for line in lines]
        by_id = {reply.get("id"): reply for reply in replies}
        if len(replies) != 5 or set(by_id) != {1, 2, 3, 4, 5}:
            raise RuntimeError(f"expected five MCP replies; got {len(replies)}")
        if any("error" in reply for reply in replies):
            raise RuntimeError("MCP returned a JSON-RPC error")
        init = by_id[1]["result"]
        if init.get("serverInfo", {}).get("name") != "spring-service-graph":
            raise RuntimeError("MCP initialize returned unexpected serverInfo")
        listed = {t.get("name") for t in by_id[2]["result"].get("tools", [])}
        if listed != set(TOOLS):
            raise RuntimeError(f"MCP tools/list mismatch: {sorted(listed)}")

        def tool_result(ident):
            envelope = by_id[ident]["result"]
            if envelope.get("isError") is not False:
                raise RuntimeError(f"MCP tool call {ident} failed: {envelope}")
            content = envelope.get("content", [])
            if not content or content[0].get("type") != "text":
                raise RuntimeError(f"MCP tool call {ident} returned no text content")
            return content[0]["text"]

        who = json.loads(tool_result(3))
        if who.get("matches") != [target_id]:
            raise RuntimeError(f"who_calls matched unexpected nodes: {who.get('matches')}")
        actual_incoming = [edge_key(e) for e in who.get("incoming", [])]
        expected = expected_incoming(graph, target_ids)
        if actual_incoming != expected:
            raise RuntimeError(f"who_calls incoming mismatch: expected {len(expected)}, got {len(actual_incoming)}")

        impact = json.loads(tool_result(4))
        if impact.get("matches") != [target_id] or impact.get("depth") != 1:
            raise RuntimeError("impact_of returned unexpected target or depth")
        actual_impact = [
            (row.get("node"), edge_key(row.get("because", {})))
            for row in impact.get("affected", [])
        ]
        expected_impact = expected_impact_depth_one(graph, target_ids)
        if actual_impact != expected_impact:
            raise RuntimeError(f"impact_of depth-one mismatch: expected {len(expected_impact)}, got {len(actual_impact)}")

        card = tool_result(5)
        if not card.strip() or service["name"].casefold() not in card.casefold():
            raise RuntimeError("service_card omitted the selected service")
        return {
            "status": "passed",
            "started_at": started_at,
            "seconds": seconds,
            "exit_code": proc.returncode,
            "stderr_log": log_path.relative_to(ROOT).as_posix(),
            "stderr_tail": proc.stderr[-2000:],
            "tool_names": sorted(listed),
            "target": target_id,
            "incoming_edges_verified": len(expected),
            "impact_depth": 1,
            "impact_rows_verified": len(expected_impact),
            "service_card_verified": service["name"],
        }
    except Exception as exc:
        seconds = round(time.monotonic() - started, 3)
        if not log_path.exists():
            log_path.parent.mkdir(parents=True, exist_ok=True)
            log_path.write_text("", encoding="utf-8")
        return {
            "status": "failed",
            "started_at": started_at,
            "seconds": seconds,
            "error": str(exc),
            "stderr_log": log_path.relative_to(ROOT).as_posix(),
        }


def run_process(command, log_dir, name, timeout):
    started_at = utc_now()
    start = time.monotonic()
    try:
        proc = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                              errors="replace", env=child_env(), timeout=timeout)
        seconds = round(time.monotonic() - start, 3)
        stderr = proc.stderr
        stdout = proc.stdout
        exit_code = proc.returncode
    except subprocess.TimeoutExpired as exc:
        seconds = round(time.monotonic() - start, 3)
        stderr = decode(exc.stderr)
        stdout = decode(exc.stdout)
        exit_code = None
    stderr_path = log_dir / f"{name}.stderr.log"
    stdout_path = log_dir / f"{name}.stdout.log"
    stderr_path.parent.mkdir(parents=True, exist_ok=True)
    stderr_path.write_text(stderr, encoding="utf-8")
    stdout_path.write_text(stdout, encoding="utf-8")
    return {
        "status": "passed" if exit_code == 0 else "failed",
        "started_at": started_at,
        "seconds": seconds,
        "exit_code": exit_code,
        "stderr_log": stderr_path.relative_to(ROOT).as_posix(),
        "stderr_bytes": len(stderr.encode("utf-8")),
        "stderr_tail": stderr[-2000:],
        "stdout_log": stdout_path.relative_to(ROOT).as_posix(),
        "stdout_tail": stdout[-1000:],
    }


def decode(value):
    if value is None:
        return ""
    return value.decode("utf-8", errors="replace") if isinstance(value, bytes) else str(value)


def validate_cached_result(result_file, row, scanner_hash, distribution_hash, run_root):
    try:
        result = json.loads(result_file.read_text(encoding="utf-8"))
        if (result.get("status") != "passed"
                or result.get("scanner_jar_sha256") != scanner_hash
                or result.get("distribution_sha256") != distribution_hash):
            return None
        if result.get("commit") != row["commit"]:
            return None
        slug = slug_for(row)
        for number in (1, 2):
            folder = run_root / f"run{number}" / slug
            if not folder.is_dir() or file_hashes(folder) != result.get("output_file_sha256"):
                return None
        return result
    except (OSError, ValueError, KeyError):
        return None


def slug_for(row):
    return row["repository"].replace("/", "--")


def append_registry(row, result, scanner_hash, distribution_hash, scanner_commit):
    # Only register a repository after both source scans actually completed.
    if len(result.get("scans", [])) != 2 or any(s.get("exit_code") != 0 for s in result["scans"]):
        return
    REGISTRY.parent.mkdir(parents=True, exist_ok=True)
    if REGISTRY.is_file():
        registry = json.loads(REGISTRY.read_text(encoding="utf-8"))
    else:
        registry = {"schema_version": 1, "runs": []}
    registry.setdefault("schema_version", 1)
    registry.setdefault("runs", [])
    key = (row["repository"], row["commit"], scanner_hash, distribution_hash,
           "default", None, None, "source_sparse")
    for prior in registry["runs"]:
        prior_key = (
            prior.get("repository"), prior.get("source_commit"),
            prior.get("scanner_jar_sha256"), prior.get("distribution_sha256"),
            prior.get("profile", "default"),
            prior.get("aliases"), prior.get("config_repo"), prior.get("scan_mode", "source"),
        )
        if prior_key == key:
            return
    started = min((s["started_at"] for s in result["scans"]), default=None)
    record = {
        "repository": row["repository"],
        "source_commit": row["commit"],
        "suite": SUITE,
        "scanned_on": datetime.now(timezone.utc).date().isoformat(),
        "started_at": started,
        "finished_at": result.get("finished_at"),
        "spring_boot_version": row.get("boot_version"),
        "build_system": [row["tool"].capitalize()],
        "java_files": row.get("measured_java_files", row.get("java_files")),
        "tier": row["tier"],
        "scanner_commit": scanner_commit,
        "scanner_jar_sha256": scanner_hash,
        "distribution_sha256": distribution_hash,
        "scanner_source_state": result.get("scanner_source_state"),
        "snapshot": "sparse source checkout: src/main, build descriptors, SQL and API specs; no foreign build",
        "profile": "default",
        "aliases": None,
        "config_repo": None,
        "scan_mode": "source_sparse",
        "successful_scans": 2,
        "deterministic_output": result.get("deterministic_output"),
        "status": "scanned",
        "validation_status": result.get("status"),
        "execution_status": result.get("execution_status"),
        "extraction_status": result.get("extraction_status"),
        "validation_evidence": result.get("result_file"),
        "historical_checkout": result.get("source_checkout", Path(row["path"]).as_posix()),
    }
    registry["runs"].append(record)
    registry["recorded_at"] = utc_now()
    json_write_atomic(REGISTRY, registry)


def run_candidate(row, scanner_hash, distribution_hash, scanner_commit, scanner_source_state, jars, run_root):
    slug = slug_for(row)
    checkout = source_checkout(row)
    result_file = run_root / "results" / f"{slug}.json"
    cached = validate_cached_result(result_file, row, scanner_hash, distribution_hash, run_root)
    if cached is not None:
        append_registry(row, cached, scanner_hash, distribution_hash, scanner_commit)
        cached["resumed"] = True
        return cached

    output_parent = run_root
    log_dir = run_root / "logs"
    scans = []
    scan_hashes = []
    errors = []
    for number in (1, 2):
        output = run_root / f"run{number}" / slug
        safe_clear_output(output, output.parent)
        output.mkdir(parents=True, exist_ok=True)
        scan = run_process(
            java_command() + ["scan", "--repos", str(checkout), "--out", str(output)],
            log_dir, f"{slug}-run{number}", 600,
        )
        scan["run"] = number
        scan["output_files"] = len(file_hashes(output))
        hashes = file_hashes(output)
        scan["output_tree_sha256"] = tree_digest(hashes)
        scans.append(scan)
        if scan["exit_code"] == 0:
            scan_hashes.append(hashes)
        else:
            errors.append(f"run{number} scan failed (exit {scan['exit_code']})")

    deterministic = len(scan_hashes) == 2 and scan_hashes[0] == scan_hashes[1]
    if len(scan_hashes) == 2 and not deterministic:
        errors.append("run1 and run2 output file hashes differ")

    validation = None
    diff = None
    mcp = None
    if all(s.get("exit_code") == 0 for s in scans):
        try:
            graph = graph_from(run_root / "run1" / slug)
            validation = process_output_evidence(graph, checkout)
            if not validation["valid"]:
                errors.extend(validation["errors"])
            diff = run_process(
                java_command() + [
                    "diff", "--base", str(run_root / "run1" / slug),
                    "--head", str(run_root / "run2" / slug), "--fail-on-breaking",
                ],
                log_dir, f"{slug}-diff-identical", 120,
            )
            if diff["exit_code"] != 0 or "No changes." not in diff["stdout_tail"]:
                errors.append("repeated-graph breaking diff did not return success with No changes")
            if validation["valid"]:
                mcp = mcp_smoke(graph, run_root / "run1" / slug, log_dir / f"{slug}-mcp.stderr.log")
                if mcp["status"] != "passed":
                    errors.append("MCP smoke failed: " + mcp.get("error", "unknown error"))
        except Exception as exc:
            errors.append(str(exc))

    scans_completed = len(scans) == 2 and all(s.get("exit_code") == 0 for s in scans)
    execution_status = "passed" if scans_completed and diff and diff["status"] == "passed" \
        and (not validation or not validation["valid"] or (mcp and mcp["status"] == "passed")) else "failed"
    extraction_status = "passed" if validation and validation["valid"] else "failed"
    status = "passed" if not errors and deterministic and validation and validation["valid"] \
        and diff and diff["status"] == "passed" and mcp and mcp["status"] == "passed" else "failed"
    result = {
        "repository": row["repository"],
        "commit": row["commit"],
        "tier": row["tier"],
        "tool": row["tool"],
        "boot_version": row.get("boot_version"),
        "java_files": row.get("measured_java_files", row.get("java_files")),
        "source_checkout": checkout.relative_to(ROOT).as_posix(),
        "scanner_commit": scanner_commit,
        "scanner_jar_sha256": scanner_hash,
        "distribution_sha256": distribution_hash,
        "scanner_source_state": scanner_source_state,
        "scanner_jars": [Path(j).name for j in jars],
        "profile": "default",
        "scan_mode": "source_sparse",
        "started_at": min((s["started_at"] for s in scans), default=utc_now()),
        "finished_at": utc_now(),
        "scans": scans,
        "deterministic_output": deterministic,
        "execution_status": execution_status,
        "extraction_status": extraction_status,
        "output_file_sha256": scan_hashes[0] if scan_hashes else {},
        "graph_validation": validation,
        "diff_identical": diff,
        "mcp": mcp,
        "status": status,
        "errors": errors,
        "result_file": result_file.relative_to(ROOT).as_posix(),
        "output_directories": [
            (run_root / f"run{n}" / slug).relative_to(ROOT).as_posix() for n in (1, 2)
        ],
    }
    json_write_atomic(result_file, result)
    append_registry(row, result, scanner_hash, distribution_hash, scanner_commit)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--qualified", type=Path, default=QUALIFIED,
                        help="final qualified manifest (default: build/fix-audit/qualified.json)")
    args = parser.parse_args()
    manifest = args.qualified if args.qualified.is_absolute() else ROOT / args.qualified
    manifest = manifest.resolve()
    if not manifest.is_relative_to(ROOT.resolve()):
        raise RuntimeError(f"Qualification manifest must remain under {ROOT}: {manifest}")
    rows = load_candidates(manifest)
    scanner_hash, distribution_hash, scanner_commit, scanner_source_state, jars = installed_scanner()
    run_root = OUT / "runs" / distribution_hash[:16]
    (run_root / "results").mkdir(parents=True, exist_ok=True)
    started = utc_now()
    summaries = []
    failed = []
    for row in rows:
        try:
            result = run_candidate(row, scanner_hash, distribution_hash, scanner_commit,
                                   scanner_source_state, jars, run_root)
        except Exception as exc:
            result = {
                "repository": row.get("repository"),
                "commit": row.get("commit"),
                "tier": row.get("tier"),
                "tool": row.get("tool"),
                "scanner_jar_sha256": scanner_hash,
                "distribution_sha256": distribution_hash,
                "scanner_source_state": scanner_source_state,
                "status": "failed",
                "errors": [str(exc)],
            }
        summaries.append({
            "repository": result.get("repository"),
            "commit": result.get("commit"),
            "tier": result.get("tier"),
            "tool": result.get("tool"),
            "status": result.get("status"),
            "execution_status": result.get("execution_status"),
            "extraction_status": result.get("extraction_status"),
            "resumed": result.get("resumed", False),
            "result_file": result.get("result_file"),
            "errors": result.get("errors", []),
        })
        if result.get("status") != "passed":
            failed.append(result.get("repository"))
        json_write_atomic(run_root / "results.json", {
            "schema_version": 1,
            "suite": SUITE,
            "manifest": manifest.relative_to(ROOT).as_posix(),
            "started_at": started,
            "updated_at": utc_now(),
            "scanner_commit": scanner_commit,
            "scanner_jar_sha256": scanner_hash,
            "distribution_sha256": distribution_hash,
            "scanner_source_state": scanner_source_state,
            "candidate_count": len(rows),
            "passed": sum(x["status"] == "passed" for x in summaries),
            "failed": len(failed),
            "execution_passed": sum(x.get("execution_status") == "passed" for x in summaries),
            "extraction_passed": sum(x.get("extraction_status") == "passed" for x in summaries),
            "results": summaries,
        })
        print(f"{row['tier']} {row['tool']} {row['repository']} {result.get('status')}", flush=True)
    print(f"Completed {len(rows)} repositories: {len(rows)-len(failed)} passed, {len(failed)} failed.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
