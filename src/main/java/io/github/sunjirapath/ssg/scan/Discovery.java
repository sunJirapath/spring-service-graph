package io.github.sunjirapath.ssg.scan;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import io.github.sunjirapath.ssg.model.Value;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Finds the services in a repo: every module with a {@code @SpringBootApplication} class. */
public final class Discovery {

    private static final List<String> EXTENSIONS = List.of("properties", "yml", "yaml");

    private Discovery() {}

    /**
     * @param configRepo optional Spring Cloud Config repo checkout; its files override local config
     */
    public static List<Service> discover(JavaIndex index, String profile, Path configRepo) {
        var apps = new TreeMap<Path, CompilationUnit>();
        for (CompilationUnit cu : index.units()) {
            if (index.isGenerated(cu)) continue;
            boolean app = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .anyMatch(c -> Ann.has(c, "SpringBootApplication"));
            if (app) apps.putIfAbsent(index.moduleOf(index.fileOf(cu)), cu);
        }
        var services = new ArrayList<Service>();
        for (Path module : apps.keySet()) {
            // shortcut: in multi-app repos, library modules (no app) are attributed to no service;
            // their constants and interfaces are still visible through the index.
            List<CompilationUnit> units = apps.size() == 1 ? index.units()
                    : index.units().stream().filter(u -> index.moduleOf(index.fileOf(u)).equals(module)).toList();
            Config config = localConfig(index.repo(), module.resolve("src/main/resources"), profile);
            Value name = config.valueOf("spring.application.name");
            String serviceName = name.resolved() ? name.text() : module.getFileName().toString();
            if (configRepo != null) config.putAll(remoteConfig(Repo.of(configRepo), serviceName, profile));
            services.add(new Service(serviceName, index.repo(), module, config, index, units));
        }
        return services;
    }

    private static Config localConfig(Repo repo, Path resources, String profile) {
        var files = new ArrayList<Path>();
        for (String base : List.of("bootstrap", "application")) {
            for (String ext : EXTENSIONS) files.add(resources.resolve(base + "." + ext));
            for (String ext : EXTENSIONS) files.add(resources.resolve(base + "-" + profile + "." + ext));
        }
        return Config.load(repo, files, profile);
    }

    // shortcut: only the config repo root is read, not per-app subfolders or search-locations.
    private static Config remoteConfig(Repo repo, String app, String profile) {
        var files = new ArrayList<Path>();
        for (String base : List.of("application", app)) {
            for (String ext : EXTENSIONS) files.add(repo.root().resolve(base + "." + ext));
            for (String ext : EXTENSIONS) files.add(repo.root().resolve(base + "-" + profile + "." + ext));
        }
        return Config.load(repo, files, profile);
    }
}
