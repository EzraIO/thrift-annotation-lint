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
        assertTrue(Files.exists(MAIN_PACKAGE.resolve(
                "internal/validation/LogicalFieldMetadataRules.java")));
        assertTrue(Files.exists(MAIN_PACKAGE.resolve(
                "internal/validation/IterativeStronglyConnectedComponents.java")));
        assertTrue(Files.exists(MAIN_PACKAGE.resolve(
                "internal/types/WireTypeSupport.java")));
        assertTrue(Files.exists(MAIN_PACKAGE.resolve(
                "internal/planning/RoundCandidateCollector.java")));
        assertTrue(Files.exists(MAIN_PACKAGE.resolve(
                "internal/planning/ModelReferenceCollector.java")));
        assertTrue(Files.exists(MAIN_PACKAGE.resolve(
                "internal/extract/ExecutableTypeResolver.java")));
        assertTrue(Files.exists(MAIN_PACKAGE.resolve(
                "internal/extract/SwiftModelDeclarationValidator.java")));
        assertTrue(Files.exists(MAIN_PACKAGE.resolve(
                "internal/extract/LombokAccessorInspector.java")));
        assertTrue(Files.exists(MAIN_PACKAGE.resolve(
                "internal/model/ThriftRuntime.java")));
        assertTrue(Files.exists(MAIN_PACKAGE.resolve(
                "internal/extract/SwiftParameterFieldExtractor.java")));
        assertTrue(Files.exists(MAIN_PACKAGE.resolve(
                "internal/extract/SwiftBuilderTypeResolver.java")));
        assertTrue(Files.exists(MAIN_PACKAGE.resolve(
                "internal/bytecode/ClassFileConstantPoolReader.java")));
        assertTrue(Files.exists(MAIN_PACKAGE.resolve(
                "internal/bytecode/MethodParameterMetadata.java")));
        assertTrue(Files.exists(MAIN_PACKAGE.resolve(
                "internal/bytecode/ParameterNameLookup.java")));
    }

    @Test
    void coreCoordinatorsRemainFocusedAfterResponsibilityExtraction()
            throws IOException {
        assertSourceAtMost("internal/validation/LogicalFieldValidator.java", 100);
        assertSourceAtMost("internal/validation/RecursiveModelCycleValidator.java", 100);
        assertSourceAtMost("internal/planning/RoundPlanner.java", 320);
        assertSourceAtMost("internal/planning/DemandClosure.java", 320);
        assertSourceAtMost("internal/types/WireTypeClassifier.java", 320);
        assertSourceAtMost("internal/extract/SwiftMemberResolver.java", 320);
        assertSourceAtMost("internal/extract/SwiftModelExtractor.java", 320);
        assertSourceAtMost("internal/extract/SwiftFieldPartExtractor.java", 320);
        assertSourceAtMost("internal/extract/SwiftConstructionExtractor.java", 320);
        assertSourceAtMost("internal/bytecode/ClassFileParameterNameParser.java", 320);
        assertSourceAtMost("internal/bytecode/MethodParameterMetadata.java", 160);
        assertSourceAtMost("internal/bytecode/ParameterNameLookup.java", 120);
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

    private void assertSourceAtMost(String relativePath, int maximumLines)
            throws IOException {
        Path source = MAIN_PACKAGE.resolve(relativePath);
        long lines;
        try (Stream<String> sourceLines = Files.lines(source, StandardCharsets.UTF_8)) {
            lines = sourceLines.count();
        }
        assertTrue(
                lines <= maximumLines,
                source + " has " + lines + " lines; keep orchestration focused at or below "
                        + maximumLines);
    }
}
