package io.github.sunjirapath.ssg.link;

import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Finding;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import io.github.sunjirapath.ssg.model.Value;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Turns per-service facts into one graph: resolves call targets (lb:// name, host = service name,
 * aliases, else external), matches call paths against target endpoints, and reports drift.
 */
public final class Linker {

    /** Facts of one service, as produced by the extractors. */
    public record ServiceFacts(String service, String repo, String path, List<Fact> facts) {}

    private record EndpointRef(String id, String method, String path) {}

    record Url(String host, String path) {}

    public static final String UNRESOLVED_TARGET = Node.External.idOf("unresolved");

    private final Map<String, String> aliases;
    private final Map<String, String> serviceByName = new HashMap<>();
    private final Map<String, List<EndpointRef>> endpoints = new HashMap<>();
    private final Map<String, Node> nodes = new TreeMap<>();
    private final Map<String, EdgeAcc> edges = new TreeMap<>();
    private final List<Finding> findings = new ArrayList<>();

    private Linker(Map<String, String> aliases) {
        this.aliases = aliases;
    }

    /** @param aliases host -> service name, or any other name for an external system */
    public static Graph link(List<ServiceFacts> all, Map<String, String> aliases, List<Finding> extraFindings) {
        var l = new Linker(aliases);
        l.findings.addAll(extraFindings);
        all = singleOwners(all);
        l.sharedTables(all);
        all.forEach(l::addService);
        all.forEach(l::addFacts);
        var es = l.edges.values().stream().map(EdgeAcc::build)
                .sorted(Comparator.comparing(Edge::from).thenComparing(Edge::kind).thenComparing(Edge::to)
                        .thenComparing(e -> e.attrs().toString()))
                .toList();
        var fs = l.findings.stream().distinct()
                .sorted(Comparator.comparing(Finding::kind).thenComparing(Finding::service).thenComparing(Finding::message)
                        .thenComparing(f -> f.evidence().toString()))
                .toList();
        return new Graph(List.copyOf(l.nodes.values()), es, fs);
    }

    /**
     * A table owned by several services (shared DDL) belongs to the one whose own module holds the entity, mapper or DDL;
     * the rest only use it. With zero or several such services nothing changes.
     */
    static List<ServiceFacts> singleOwners(List<ServiceFacts> all) {
        var owners = new HashMap<String, List<ServiceFacts>>();
        for (var sf : all)
            sf.facts().stream().filter(f -> f instanceof Fact.TableUse t && t.owns()).map(f -> ((Fact.TableUse) f).store() + ":" + ((Fact.TableUse) f).table().text())
                    .distinct().forEach(t -> owners.computeIfAbsent(t, k -> new ArrayList<>()).add(sf));
        var demote = new HashMap<String, java.util.Set<String>>(); // service -> tables it only uses
        owners.forEach((table, list) -> {
            if (list.size() < 2) return;
            var own = list.stream().filter(sf -> sf.facts().stream().anyMatch(f -> f instanceof Fact.TableUse t
                    && (t.store() + ":" + t.table().text()).equals(table) && inModule(sf, t.evidence()))).toList();
            if (own.size() != 1) return;
            for (var sf : list) if (sf != own.get(0)) demote.computeIfAbsent(sf.service(), k -> new java.util.HashSet<>()).add(table);
        });
        if (demote.isEmpty()) return all;
        return all.stream().map(sf -> {
            var tables = demote.get(sf.service());
            if (tables == null) return sf;
            var facts = sf.facts().stream().map(f -> f instanceof Fact.TableUse t && t.owns() && tables.contains(t.store() + ":" + t.table().text())
                    ? (Fact) new Fact.TableUse(false, t.table(), t.store(), t.evidence(), t.via()) : f).toList();
            return new ServiceFacts(sf.service(), sf.repo(), sf.path(), facts);
        }).toList();
    }

    /** A table still owned by several services after {@link #singleOwners}: a shared schema couples their releases. */
    private void sharedTables(List<ServiceFacts> all) {
        var owners = new TreeMap<String, java.util.TreeSet<String>>();
        var evidence = new HashMap<String, List<Evidence>>();
        for (var sf : all)
            for (Fact f : sf.facts())
                if (f instanceof Fact.TableUse t && t.owns() && t.table().resolved()) {
                    String key = t.store() + ":" + t.table().text();
                    if (owners.computeIfAbsent(key, k -> new java.util.TreeSet<>()).add(sf.service()))
                        evidence.computeIfAbsent(key, k -> new ArrayList<>()).add(t.evidence());
                }
        // one finding per set of owners: a shared -dao module would otherwise repeat it for every table
        var groups = new java.util.LinkedHashMap<java.util.TreeSet<String>, List<String>>();
        owners.forEach((key, services) -> {
            if (services.size() >= 2) groups.computeIfAbsent(services, k -> new ArrayList<>()).add(key);
        });
        groups.forEach((services, keys) -> {
            var tables = keys.stream().map(k -> "`" + k.substring(k.indexOf(':') + 1) + "`").distinct().toList();
            findings.add(new Finding(Finding.Kind.SHARED_TABLE, services.first(), (tables.size() == 1 ? "table " : "tables ")
                    + String.join(", ", tables) + (tables.size() == 1 ? " is" : " are") + " owned by " + services.size()
                    + " services: " + String.join(", ", services), keys.stream().flatMap(k -> evidence.get(k).stream()).toList()));
        });
    }

    private static boolean inModule(ServiceFacts sf, Evidence e) {
        return e.repo().equals(sf.repo()) && (sf.path().equals(".") || e.file().startsWith(sf.path() + "/"));
    }

    private void addService(ServiceFacts sf) {
        String id = Node.Service.idOf(sf.service());
        nodes.put(id, new Node.Service(id, sf.service(), sf.repo(), sf.path()));
        serviceByName.put(sf.service().toLowerCase(Locale.ROOT), sf.service());
        for (Fact f : sf.facts()) {
            if (!(f instanceof Fact.Endpoint e)) continue;
            String path = e.path().text();
            String eid = Node.Endpoint.idOf(sf.service(), e.method(), path);
            nodes.put(eid, new Node.Endpoint(eid, sf.service(), e.method(), path));
            endpoints.computeIfAbsent(sf.service(), k -> new ArrayList<>()).add(new EndpointRef(eid, e.method(), path));
            // second evidence: the handler a reader edits (the mapping may sit on a generated interface)
            Value exposed = e.handler() == null ? e.path() : e.path().withConfidence(e.path().confidence(), e.handler());
            edge(Edge.Kind.EXPOSES, id, eid, exposed, e.evidence(), attrs("source", e.source()));
            if (!e.path().resolved()) unresolved(sf.service(), "endpoint " + e.method() + " " + path, e.path(), e.evidence());
        }
    }

    private void addFacts(ServiceFacts sf) {
        String from = Node.Service.idOf(sf.service());
        var sinkTargets = new HashMap<Evidence, List<String>>(); // where a fact was found -> nodes it links to
        for (Fact f : sf.facts()) {
            switch (f) {
                case Fact.Endpoint e -> { }
                case Fact.Reach r -> { } // after all sinks are linked, below
                case Fact.Call c -> sinkTargets.computeIfAbsent(c.evidence(), k -> new ArrayList<>()).addAll(call(sf.service(), from, c));
                case Fact.TopicUse t -> {
                    String name = t.topic().resolved() ? t.topic().text() : "?" + t.topic().raw();
                    String tid = Node.Topic.idOf(t.broker(), name);
                    nodes.putIfAbsent(tid, new Node.Topic(tid, name, t.broker()));
                    var kind = t.produce() ? Edge.Kind.PRODUCES : Edge.Kind.CONSUMES;
                    edge(kind, from, tid, t.topic(), t.evidence(), attrs("via", t.via()));
                    sinkTargets.computeIfAbsent(t.evidence(), k -> new ArrayList<>()).add(tid);
                    if (!t.topic().resolved()) {
                        unresolved(sf.service(), (t.produce() ? "produced" : "consumed") + " topic", t.topic(), t.evidence());
                    }
                }
                case Fact.TableUse t -> {
                    String tid = Node.Table.idOf(sf.service(), t.table().text(), t.store());
                    nodes.putIfAbsent(tid, new Node.Table(tid, sf.service(), t.table().text(), t.store()));
                    edge(t.owns() ? Edge.Kind.OWNS : Edge.Kind.USES, from, tid, t.table(), t.evidence(),
                        "@Query".equals(t.via()) ? attrs("via", "@Query") : new TreeMap<>());
                    sinkTargets.computeIfAbsent(t.evidence(), k -> new ArrayList<>()).add(tid);
                    if (!t.table().resolved()) unresolved(sf.service(), "table", t.table(), t.evidence());
                }
                case Fact.Problem p -> findings.add(new Finding(Finding.Kind.valueOf(p.kind()), sf.service(), p.message(),
                        List.of(p.evidence())));
            }
        }
        for (Fact f : sf.facts()) {
            if (!(f instanceof Fact.Reach r)) continue;
            String eid = Node.Endpoint.idOf(sf.service(), r.method(), r.path());
            var via = new Value("", Confidence.RESOLVED, "", List.of(r.handler()));
            for (String target : sinkTargets.getOrDefault(r.sink(), List.of())) {
                edge(Edge.Kind.REACHES, eid, target, via, r.sink(), attrs("chain", r.chain()));
            }
        }
    }

    /** Links one call and returns the node ids it now points to. */
    private List<String> call(String service, String from, Fact.Call c) {
        Value value = new Value(redactUrl(c.url().text()), c.url().confidence(), redactUrl(c.url().raw()), c.url().via());
        Url url = parse(value.text());
        var attrs = attrs("client", c.client(), "method", c.method(), "path", url.path());
        if (!value.resolved()) attrs.put("raw", value.raw());
        if (!value.resolved()) unresolved(service, c.client() + " call " + c.method() + " " + value.text(), value, c.evidence());

        String target = url.host() == null ? null : resolveHost(url.host());
        if (target == null) {
            attrs.put("raw", value.raw()); // without a target, the source expression is all a reader has
            nodes.putIfAbsent(UNRESOLVED_TARGET, new Node.External(UNRESOLVED_TARGET, "unresolved"));
            edge(Edge.Kind.CALLS, from, UNRESOLVED_TARGET, value, c.evidence(), attrs);
            return List.of(UNRESOLVED_TARGET);
        }
        if (target.startsWith("external:")) {
            nodes.putIfAbsent(target, new Node.External(target, target.substring("external:".length())));
            edge(Edge.Kind.CALLS, from, target, value, c.evidence(), attrs);
            return List.of(target);
        }
        var matched = endpoints.getOrDefault(target, List.of()).stream()
                .filter(e -> methodMatches(c.method(), e.method()) && PathMatch.matches(url.path(), e.path()))
                .toList();
        if (matched.stream().anyMatch(e -> e.path().equals(url.path()))) // an exact literal beats a template sibling
            matched = matched.stream().filter(e -> e.path().equals(url.path())).toList();
        for (EndpointRef e : matched) edge(Edge.Kind.CALLS, from, e.id(), value, c.evidence(), attrs);
        if (!matched.isEmpty()) return matched.stream().map(EndpointRef::id).toList();
        edge(Edge.Kind.CALLS, from, Node.Service.idOf(target), value, c.evidence(), attrs);
        if (!url.path().contains(Value.UNKNOWN)) {
            findings.add(new Finding(Finding.Kind.CONTRACT_DRIFT, service, c.client() + " call " + c.method() + " "
                    + url.path() + " matches no endpoint exposed by " + target, List.of(c.evidence())));
        }
        return List.of(Node.Service.idOf(target));
    }

    /** Service name, {@code external:<name>}, or null when the host is not known statically. */
    private String resolveHost(String host) {
        if (host.contains(Value.UNKNOWN) || host.isEmpty()) return null;
        String svc = serviceByName.get(host.toLowerCase(Locale.ROOT));
        if (svc != null) return svc;
        String alias = aliases.get(host);
        if (alias != null) {
            svc = serviceByName.get(alias.toLowerCase(Locale.ROOT));
            return svc != null ? svc : Node.External.idOf(alias);
        }
        return Node.External.idOf(host);
    }

    private static final Pattern USERINFO = Pattern.compile("^[^@/]*@");
    private static final Pattern URL_USERINFO = Pattern.compile("(?<=://)[^/?#]*@");

    /** Also covers raw concatenations: a query's later operands may contain credentials. */
    private static String redactUrl(String value) {
        String safe = value.replace(Value.UNKNOWN, "\0");
        safe = URL_USERINFO.matcher(safe).replaceAll("[redacted]@");
        int end = safe.length();
        for (char delimiter : new char[]{'?', '#'}) {
            int at = safe.indexOf(delimiter);
            if (at >= 0) end = Math.min(end, at);
        }
        if (end < safe.length()) safe = safe.substring(0, end) + safe.charAt(end) + "[redacted]";
        return safe.replace("\0", Value.UNKNOWN);
    }

    /** Splits {@code scheme://user@host:port/path?q} into host and path; host is null for relative URLs. */
    static Url parse(String url) {
        String rest = url.replace(Value.UNKNOWN, "\0"); // protect the '?' in unresolved placeholders
        int i = rest.indexOf("://");
        String host = null;
        if (i >= 0 && !rest.substring(0, i).contains("\0")) {
            rest = rest.substring(i + 3);
            int end = rest.length();
            for (char delimiter : new char[]{'/', '?', '#'}) {
                int at = rest.indexOf(delimiter);
                if (at >= 0) end = Math.min(end, at);
            }
            String authority = rest.substring(0, end);
            rest = end == rest.length() ? "/" : rest.substring(end);
            authority = USERINFO.matcher(authority).replaceFirst(""); // never keep credentials
            int colon = authority.lastIndexOf(':');
            int bracket = authority.startsWith("[") ? authority.indexOf(']') : -1;
            host = bracket >= 0 ? authority.substring(0, bracket + 1) : colon > 0 ? authority.substring(0, colon) : authority;
            host = host.replace("\0", Value.UNKNOWN);
        }
        int q = rest.indexOf('?');
        if (q >= 0) rest = rest.substring(0, q);
        int h = rest.indexOf('#');
        if (h >= 0) rest = rest.substring(0, h);
        rest = rest.replace("\0", Value.UNKNOWN);
        return new Url(host, rest.isEmpty() ? "/" : rest);
    }

    private static boolean methodMatches(String a, String b) {
        return a.equals("*") || b.equals("*") || a.equalsIgnoreCase(b);
    }

    private void unresolved(String service, String what, Value v, Evidence ev) {
        findings.add(new Finding(Finding.Kind.UNRESOLVED, service, what + " (from `" + v.raw() + "`)", List.of(ev)));
    }

    private static TreeMap<String, String> attrs(String... kv) {
        var m = new TreeMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private void edge(Edge.Kind kind, String from, String to, Value v, Evidence ev, TreeMap<String, String> attrs) {
        String key = from + "|" + kind + "|" + to + "|" + attrs;
        var acc = edges.computeIfAbsent(key, k -> new EdgeAcc(kind, from, to, attrs));
        acc.confidence = acc.evidence.isEmpty() ? v.confidence()
                : acc.confidence.compareTo(v.confidence()) <= 0 ? acc.confidence : v.confidence();
        acc.evidence.add(ev);
        acc.via.addAll(v.via());
    }

    /** Edges found more than once (e.g. one table in two migrations) merge their evidence. */
    private static final class EdgeAcc {
        final Edge.Kind kind;
        final String from;
        final String to;
        final TreeMap<String, String> attrs;
        final TreeSet<Evidence> evidence = new TreeSet<>();
        /** Where constants and properties used by the edge are defined; listed after the call sites. */
        final TreeSet<Evidence> via = new TreeSet<>();
        Confidence confidence = Confidence.UNRESOLVED;

        EdgeAcc(Edge.Kind kind, String from, String to, TreeMap<String, String> attrs) {
            this.kind = kind;
            this.from = from;
            this.to = to;
            this.attrs = attrs;
        }

        Edge build() {
            var all = new ArrayList<>(evidence);
            via.stream().filter(e -> !evidence.contains(e)).forEach(all::add);
            return new Edge(kind, from, to, confidence, attrs, List.copyOf(all));
        }
    }
}
