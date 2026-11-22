package com.company.requests.bootstrap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathNodes;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Node;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReactorGraphTest {

    private static final String PROJECT_GROUP = "com.company.requests";
    private static final Path DEPENDENCY_TREE = Path.of("target", "requests-dependency-tree.json");
    private static final Map<String, List<String>> PROJECT_EDGES = Map.ofEntries(
            Map.entry("domain", List.of()),
            Map.entry("application", List.of("domain")),
            Map.entry("adapter-http", List.of("application", "domain")),
            Map.entry("adapter-jdbc", List.of("application", "domain")),
            Map.entry("adapter-authority", List.of("application", "domain")),
            Map.entry("integration", List.of("application", "domain")),
            Map.entry("adapter-integration-jdbc", List.of("integration", "application", "domain")),
            Map.entry("adapter-vendor", List.of("integration", "application", "domain")),
            Map.entry("profile", List.of("domain", "application")),
            Map.entry("bootstrap", List.of("domain", "application", "adapter-http", "adapter-jdbc",
                    "adapter-authority", "integration", "adapter-integration-jdbc", "adapter-vendor", "profile")));

    @Test
    void matchesTheApprovedModuleGraph() throws Exception {
        var root = Path.of(System.getProperty("requests.reactorRoot"));
        var modules = modulesIn(root);
        assertEquals(PROJECT_EDGES.keySet().stream().sorted().toList(), modules, "Reactor modules");

        var mapper = JsonMapper.builder().build();
        for (var module : modules) {
            var tree = mapper.readTree(root.resolve(module).resolve(DEPENDENCY_TREE));
            assertEquals(PROJECT_GROUP, tree.path("groupId").stringValue(), "Group ID for " + module);
            assertEquals(module, tree.path("artifactId").stringValue(), "Artifact ID for " + module);
            var edges = tree.path("children").valueStream()
                    .filter(dependency -> PROJECT_GROUP.equals(dependency.path("groupId").stringValue()))
                    .map(dependency -> dependency.path("artifactId").stringValue()
                            + ":" + dependency.path("scope").stringValue())
                    .sorted()
                    .toList();
            var expected = PROJECT_EDGES.get(module).stream()
                    .map(dependency -> dependency + ":compile")
                    .sorted()
                    .toList();
            assertEquals(expected, edges, "Project dependencies for " + module);
        }
    }

    private static List<String> modulesIn(Path root) throws Exception {
        var factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var pom = factory.newDocumentBuilder().parse(root.resolve("pom.xml").toFile());
        var xpath = XPathFactory.newDefaultInstance().newXPath();
        var modules = xpath.evaluateExpression("/project/modules/module", pom, XPathNodes.class);
        return StreamSupport.stream(modules.spliterator(), false)
                .map(Node::getTextContent)
                .map(String::strip)
                .sorted()
                .toList();
    }
}
