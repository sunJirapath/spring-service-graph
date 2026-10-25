package io.github.sunjirapath.ssg.scan;

import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Literal Spring XML bean registrations inside the service's runtime resource roots. */
final class XmlBeans {
    // shortcut: XML profiles, custom namespaces and factory execution are ignored; extend when a service needs them.
    private static final String BEANS = "http://www.springframework.org/schema/beans";
    private final Config config;
    private final List<Path> roots;
    private final Set<Path> seen = new HashSet<>();

    XmlBeans(Config config, List<Path> roots) {
        this.config = config;
        this.roots = List.copyOf(roots);
    }

    Set<String> classes(String location) {
        var classes = new LinkedHashSet<String>();
        var pending = new ArrayDeque<Resource>();
        add(location, null, pending);
        while (!pending.isEmpty()) {
            Resource resource = pending.pop();
            try {
                if (!seen.add(resource.file().toRealPath())) continue;
                var factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
                var builder = factory.newDocumentBuilder();
                builder.setErrorHandler(new DefaultHandler());
                try (var input = Files.newInputStream(resource.file())) {
                    var elements = builder.parse(input).getElementsByTagName("*");
                    for (int i = 0; i < elements.getLength(); i++) {
                        Element element = (Element) elements.item(i);
                        String namespace = element.getNamespaceURI();
                        if (namespace != null && !namespace.isEmpty() && !namespace.equals(BEANS)) continue;
                        String name = element.getLocalName();
                        if ("bean".equals(name)) {
                            var value = config.resolve(element.getAttribute("class"));
                            if (value.resolved() && !value.text().isBlank()) classes.add(value.text().strip().replace('$', '.'));
                        } else if ("import".equals(name)) add(element.getAttribute("resource"), resource, pending);
                    }
                }
            } catch (IOException | SAXException | ParserConfigurationException | IllegalArgumentException ignored) {
                // Missing, invalid or unsafe XML supplies no registrations; never widen the component scan.
            }
        }
        return classes;
    }

    private void add(String location, Resource parent, ArrayDeque<Resource> pending) {
        var value = config.resolve(location);
        if (!value.resolved()) return;
        String path = value.text().strip().replace('\\', '/');
        boolean classpath = path.startsWith("classpath:") || path.startsWith("classpath*:");
        if (classpath) path = path.substring(path.indexOf(':') + 1);
        // Only literal classpath/local-relative resources: never URLs, filesystem locations or glob expansion.
        if (path.isBlank() || path.contains(":") || path.startsWith("//") || path.contains("*") || path.contains("?")) return;
        try {
            if (!classpath && parent != null && !path.startsWith("/")) {
                add(parent.root(), parent.file().getParent().resolve(path), pending);
            } else {
                String relative = path.replaceAll("^/+", "");
                for (Path root : roots) add(root, root.resolve(relative), pending);
            }
        } catch (InvalidPathException ignored) {
            // An invalid local resource name cannot register a bean.
        }
    }

    private static void add(Path root, Path file, ArrayDeque<Resource> pending) {
        if (Discovery.inside(root, file) && Files.isRegularFile(file)) pending.add(new Resource(root, file));
    }

    private record Resource(Path root, Path file) {}
}
