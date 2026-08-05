package io.github.thriftannotationlint;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static io.github.thriftannotationlint.CompilerTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LombokCompatibilityTest {
    @Test
    void acceptsAllDialectsOnLombokGeneratedPublicAccessors() {
        JavacResult result = compileWithLombok(
                source("example.SwiftLombokValue",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "import lombok.Data;",
                        "import lombok.Getter;",
                        "import lombok.Setter;",
                        "@Data",
                        "@ThriftStruct",
                        "public class SwiftLombokValue {",
                        "  @Getter(onMethod_ = @ThriftField(1))",
                        "  @Setter(onMethod_ = @ThriftField)",
                        "  private String name;",
                        "}"),
                source("example.AirliftDriftLombokValue",
                        "package example;",
                        "import io.airlift.drift.annotations.ThriftField;",
                        "import io.airlift.drift.annotations.ThriftStruct;",
                        "import lombok.Data;",
                        "import lombok.Getter;",
                        "import lombok.Setter;",
                        "@Data",
                        "@ThriftStruct",
                        "public class AirliftDriftLombokValue {",
                        "  @Getter(onMethod_ = @ThriftField(1))",
                        "  @Setter(onMethod_ = @ThriftField)",
                        "  private String name;",
                        "}"),
                source("example.PrestoDriftLombokValue",
                        "package example;",
                        "import com.facebook.drift.annotations.ThriftField;",
                        "import com.facebook.drift.annotations.ThriftStruct;",
                        "import lombok.Data;",
                        "import lombok.Getter;",
                        "import lombok.Setter;",
                        "@Data",
                        "@ThriftStruct",
                        "public class PrestoDriftLombokValue {",
                        "  @Getter(onMethod_ = @ThriftField(1))",
                        "  @Setter(onMethod_ = @ThriftField)",
                        "  private String name;",
                        "}"));

        assertTrue(result.succeeded(), result.output());
        assertTrue(!result.output().contains("AW"), result.output());
    }

    @Test
    void rejectsPrivateAnnotatedFieldEvenWhenLombokGeneratesAccessors() {
        JavacResult result = compileWithLombok(
                source("example.InvalidLombokValue",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "import lombok.Data;",
                        "@Data",
                        "@ThriftStruct",
                        "public class InvalidLombokValue {",
                        "  @ThriftField(1)",
                        "  private String name;",
                        "}"));

        assertTrue(!result.succeeded(), result.output());
        assertTrue(
                result.output()
                        .contains("Lombok-generated public accessors using field-level "
                                + "@Getter/@Setter(onMethod_)"),
                result.output());
    }

    private JavacResult compileWithLombok(CompilerTestSupport.Source... sources) {
        Path outputDirectory = null;
        try {
            outputDirectory = Files.createTempDirectory("thrift-annotation-lint-lombok-test-");
            List<Path> sourceFiles = writeSources(outputDirectory, sources);
            return runJavac(outputDirectory, sourceFiles);
        }
        catch (IOException e) {
            throw new AssertionError("Could not create the Lombok compiler test directory", e);
        }
        finally {
            deleteRecursively(outputDirectory);
        }
    }

    private List<Path> writeSources(
            Path outputDirectory,
            CompilerTestSupport.Source... sources) throws IOException {
        List<Path> sourceFiles = new ArrayList<Path>();
        for (CompilerTestSupport.Source source : sources) {
            String sourcePath = source.toUri().getPath();
            Path sourceFile = outputDirectory.resolve(sourcePath.substring(1));
            Files.createDirectories(sourceFile.getParent());
            Files.write(sourceFile, source.getCharContent(false).toString().getBytes(StandardCharsets.UTF_8));
            sourceFiles.add(sourceFile);
        }
        return sourceFiles;
    }

    private JavacResult runJavac(Path outputDirectory, List<Path> sourceFiles) {
        List<String> command = new ArrayList<String>();
        command.add(javacPath().toString());
        command.addAll(Arrays.asList(
                "-source", "8",
                "-target", "8",
                "-Xlint:-options",
                "-proc:only",
                "-classpath", testClasspath(),
                "-processorpath", testClasspath(),
                "-processor",
                "lombok.launch.AnnotationProcessorHider$AnnotationProcessor,"
                        + "io.github.thriftannotationlint.ThriftAnnotationLintProcessor",
                "-d", outputDirectory.toString()));
        for (Path sourceFile : sourceFiles) {
            command.add(sourceFile.toString());
        }

        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String output = readFully(process.getInputStream());
            return new JavacResult(process.waitFor(), output);
        }
        catch (IOException e) {
            throw new AssertionError("Could not start the Lombok compiler integration test", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while running the Lombok compiler integration test", e);
        }
    }

    private void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                }
                catch (IOException ignored) {
                    // Temporary compiler output is best-effort cleanup only.
                }
            });
        }
        catch (IOException ignored) {
            // A failed cleanup must not hide the compiler result.
        }
    }

    private Path javacPath() {
        Path javaHome = Paths.get(System.getProperty("java.home"));
        Path javac = javaHome.resolve("bin/javac");
        if (Files.isExecutable(javac)) {
            return javac;
        }
        javac = javaHome.resolve("../bin/javac").normalize();
        if (Files.isExecutable(javac)) {
            return javac;
        }
        throw new AssertionError("Could not find javac under " + javaHome);
    }

    private String testClasspath() {
        String surefireClasspath = System.getProperty("surefire.test.class.path");
        return surefireClasspath == null || surefireClasspath.isEmpty()
                ? System.getProperty("java.class.path")
                : surefireClasspath;
    }

    private String readFully(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final class JavacResult {
        private final int exitCode;
        private final String output;

        private JavacResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        private boolean succeeded() {
            return exitCode == 0;
        }

        private String output() {
            return output;
        }
    }
}
