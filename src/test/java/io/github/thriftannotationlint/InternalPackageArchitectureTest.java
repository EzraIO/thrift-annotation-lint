package io.github.thriftannotationlint;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalPackageArchitectureTest {
    private static final Path MAIN_PACKAGE = Paths.get(
            "src/main/java/io/github/thriftannotationlint");
    private static final Pattern INTERNAL_REFERENCE = Pattern.compile(
            "io\\.github\\.thriftannotationlint\\.internal\\.([a-z]+)\\.");

    @Test
    void rootProductionPackageContainsOnlyTheSupportedProcessorEntryPoint()
            throws IOException {
        List<String> sources = new ArrayList<String>();
        try (Stream<Path> paths = Files.list(MAIN_PACKAGE)) {
            paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".java"))
                    .forEach(sources::add);
        }
        Collections.sort(sources);
        assertEquals(
                Collections.singletonList("ThriftAnnotationLintProcessor.java"),
                sources);
    }

    @Test
    void internalPackagesFollowTheDeclaredAcyclicDependencyDirection()
            throws IOException {
        Map<String, Set<String>> allowed = new LinkedHashMap<String, Set<String>>();
        allowed.put("bytecode", packages());
        allowed.put("config", packages());
        allowed.put("model", packages());
        allowed.put("types", packages("model"));
        allowed.put("diagnostic", packages("config", "model"));
        allowed.put("extract", packages("bytecode", "diagnostic", "model", "types"));
        allowed.put("validation", packages("diagnostic", "model", "types"));
        allowed.put("planning", packages(
                "config", "diagnostic", "extract", "model", "types", "validation"));

        Path internalRoot = MAIN_PACKAGE.resolve("internal");
        try (Stream<Path> paths = Files.walk(internalRoot)) {
            for (Path source : (Iterable<Path>) paths.filter(
                    path -> Files.isRegularFile(path)
                            && path.toString().endsWith(".java"))::iterator) {
                String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
                String owner = internalRoot.relativize(source).getName(0).toString();
                if ("package-info.java".equals(source.getFileName().toString())
                        && internalRoot.equals(source.getParent())) {
                    continue;
                }
                assertTrue(allowed.containsKey(owner), "Undeclared internal package: " + owner);
                assertFalse(
                        text.contains("import io.github.thriftannotationlint.ThriftAnnotationLintProcessor;"),
                        source + " must not depend on the public processor facade");
                Matcher references = INTERNAL_REFERENCE.matcher(text);
                while (references.find()) {
                    String dependency = references.group(1);
                    if (!owner.equals(dependency)) {
                        assertTrue(
                                allowed.get(owner).contains(dependency),
                                owner + " must not depend on " + dependency + " in " + source);
                    }
                }
            }
        }
    }

    @Test
    void orchestrationAndLogicalResolutionHaveDedicatedOwners() {
        assertTrue(Files.exists(MAIN_PACKAGE.resolve(
                "internal/planning/RoundValidationEngine.java")));
        assertTrue(Files.exists(MAIN_PACKAGE.resolve(
                "internal/validation/LogicalFieldResolver.java")));
        assertFalse(Files.exists(MAIN_PACKAGE.resolve(
                "internal/extract/LogicalFieldResolver.java")));
        assertTrue(Files.exists(MAIN_PACKAGE.resolve(
                "internal/types/ThriftTypeInspector.java")));
        assertTrue(Files.exists(MAIN_PACKAGE.resolve(
                "internal/types/WireTypeClassifier.java")));
        assertTrue(Files.exists(MAIN_PACKAGE.resolve(
                "internal/types/NormalizedWireTypeFormatter.java")));
        assertTrue(Files.exists(MAIN_PACKAGE.resolve(
                "internal/types/CarrierShapeClassifier.java")));
    }

    @Test
    void userDocumentationDoesNotPresentInternalTypesAsApi() throws IOException {
        List<Path> roots = Arrays.asList(Paths.get("README.md"), Paths.get("docs"), Paths.get("examples"));
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.isDirectory(root) ? Files.walk(root) : Stream.of(root)) {
                for (Path file : (Iterable<Path>) paths.filter(
                        path -> Files.isRegularFile(path)
                                && path.toString().endsWith(".md"))::iterator) {
                    String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                    assertFalse(
                            text.contains("io.github.thriftannotationlint.internal"),
                            file + " must not document unsupported internal types");
                }
            }
        }
    }

    private static Set<String> packages(String... values) {
        return new LinkedHashSet<String>(Arrays.asList(values));
    }
}
