package io.github.thriftannotationlint;

import org.junit.jupiter.api.Assertions;

import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class CompilerTestSupport {
    private static final Pattern RULE_PREFIX = Pattern.compile("^\\[AW\\d{4}] .+");
    private static final Pattern CJK_CHARACTER = Pattern.compile("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]");

    private CompilerTestSupport() {
    }

    static Source source(String className, String... lines) {
        return new Source(className, String.join("\n", lines));
    }

    static CompilationResult compile(Source... sources) {
        return compileWithOptions(Collections.<String>emptyList(), sources);
    }

    static CompilationResult compileWithOptions(List<String> processorOptions, Source... sources) {
        return compileWithLanguageLevel(
                "8", processorOptions, Collections.<Processor>emptyList(), sources);
    }

    static CompilationResult compileAgainstClasspath(Source[] dependencySources, Source... sources) {
        return compileAgainstClasspath(
                Collections.<String>emptyList(),
                null,
                Collections.<Processor>emptyList(),
                dependencySources,
                sources);
    }

    static CompilationResult compileAgainstMutatedClasspath(
            DependencyOutputMutator mutator,
            Source[] dependencySources,
            Source... sources) {
        return compileAgainstClasspath(
                Collections.<String>emptyList(),
                mutator,
                Collections.<Processor>emptyList(),
                dependencySources,
                sources);
    }

    static CompilationResult compileAgainstClasspathWithAdditionalProcessor(
            Processor processor,
            Source[] dependencySources,
            Source... sources) {
        return compileAgainstClasspath(
                Collections.<String>emptyList(),
                null,
                Collections.singletonList(processor),
                dependencySources,
                sources);
    }

    static CompilationResult compileAgainstClasspathWithoutDebug(
            Source[] dependencySources,
            Source... sources) {
        return compileAgainstClasspath(
                Collections.singletonList("-g:none"),
                null,
                Collections.<Processor>emptyList(),
                dependencySources,
                sources);
    }

    static CompilationResult compileAgainstClasspathWithMethodParametersOnly(
            Source[] dependencySources,
            Source... sources) {
        return compileAgainstClasspath(
                Arrays.asList("-g:none", "-parameters"),
                null,
                Collections.<Processor>emptyList(),
                dependencySources,
                sources);
    }

    private static CompilationResult compileAgainstClasspath(
            List<String> dependencyOptions,
            DependencyOutputMutator mutator,
            List<Processor> additionalProcessors,
            Source[] dependencySources,
            Source... sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assertions.assertNotNull(compiler, "Tests must run on a JDK, not a JRE");

        Path temporaryRoot;
        try {
            temporaryRoot = Files.createTempDirectory("thrift-annotation-lint-classpath-test-");
        }
        catch (IOException e) {
            throw new AssertionError("Could not create classpath test directory", e);
        }

        Path dependencyOutput = temporaryRoot.resolve("dependencies");
        Path currentOutput = temporaryRoot.resolve("current");
        try {
            Files.createDirectories(dependencyOutput);
            Files.createDirectories(currentOutput);
            compileDependencies(
                    compiler, dependencyOutput, dependencyOptions, dependencySources);
            if (mutator != null) {
                mutator.mutate(dependencyOutput);
            }

            DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<JavaFileObject>();
            StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                    collector,
                    Locale.ENGLISH,
                    StandardCharsets.UTF_8);
            try {
                fileManager.setLocation(
                        StandardLocation.CLASS_OUTPUT,
                        Collections.singletonList(currentOutput.toFile()));
                String classpath = dependencyOutput.toString()
                        + File.pathSeparator
                        + testClasspath();
                setClasspath(fileManager, classpath);
                List<String> options = compilerOptions(true);
                List<JavaFileObject> compilationUnits = new ArrayList<JavaFileObject>();
                compilationUnits.addAll(Arrays.asList(sources));
                JavaCompiler.CompilationTask task = compiler.getTask(
                        null,
                        fileManager,
                        collector,
                        options,
                        null,
                        compilationUnits);
                List<Processor> processors = new ArrayList<Processor>();
                processors.add(new ThriftAnnotationLintProcessor());
                processors.addAll(additionalProcessors);
                task.setProcessors(processors);
                boolean successful = Boolean.TRUE.equals(task.call());
                return new CompilationResult(successful, collector.getDiagnostics());
            }
            finally {
                closeQuietly(fileManager);
            }
        }
        catch (IOException e) {
            throw new AssertionError("Could not configure the classpath compiler test", e);
        }
        finally {
            deleteRecursively(temporaryRoot);
        }
    }

    private static void compileDependencies(
            JavaCompiler compiler,
            Path outputDirectory,
            List<String> dependencyOptions,
            Source[] dependencySources) throws IOException {
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                collector,
                Locale.ENGLISH,
                StandardCharsets.UTF_8);
        try {
            fileManager.setLocation(
                    StandardLocation.CLASS_OUTPUT,
                    Collections.singletonList(outputDirectory.toFile()));
            setClasspath(fileManager, testClasspath());
            List<JavaFileObject> compilationUnits = new ArrayList<JavaFileObject>();
            compilationUnits.addAll(Arrays.asList(dependencySources));
            List<String> options = compilerOptions(false);
            options.addAll(dependencyOptions);
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    collector,
                    options,
                    null,
                    compilationUnits);
            boolean successful = Boolean.TRUE.equals(task.call());
            if (!successful) {
                CompilationResult result = new CompilationResult(false, collector.getDiagnostics());
                throw new AssertionError(
                        "Dependency fixture compilation failed:\n" + result.diagnosticSummary());
            }
        }
        finally {
            closeQuietly(fileManager);
        }
    }

    private static List<String> compilerOptions(boolean processingOnly) {
        List<String> options = new ArrayList<String>(Arrays.asList(
                "-source", "8",
                "-target", "8",
                "-Xlint:-options",
                "-g"));
        options.add(processingOnly ? "-proc:only" : "-proc:none");
        return options;
    }

    private static void setClasspath(
            StandardJavaFileManager fileManager,
            String classpath) throws IOException {
        List<File> entries = new ArrayList<File>();
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
            if (!entry.isEmpty()) {
                entries.add(new File(entry));
            }
        }
        fileManager.setLocation(StandardLocation.CLASS_PATH, entries);
    }

    private static String testClasspath() {
        String surefireClasspath = System.getProperty("surefire.test.class.path");
        return surefireClasspath == null || surefireClasspath.isEmpty()
                ? System.getProperty("java.class.path")
                : surefireClasspath;
    }

    private static void closeQuietly(StandardJavaFileManager fileManager) {
        try {
            fileManager.close();
        }
        catch (IOException ignored) {
            // A failed close must not hide the compiler result.
        }
    }

    static CompilationResult compileWithLanguageLevel(String languageLevel, Source... sources) {
        return compileWithLanguageLevel(
                languageLevel,
                Collections.<String>emptyList(),
                Collections.<Processor>emptyList(),
                sources);
    }

    static CompilationResult compileWithAdditionalProcessor(
            Processor processor,
            Source... sources) {
        return compileWithLanguageLevel(
                "8",
                Collections.<String>emptyList(),
                Collections.singletonList(processor),
                sources);
    }

    static CompilationResult compileWithOptionsAndAdditionalProcessor(
            List<String> processorOptions,
            Processor processor,
            Source... sources) {
        return compileWithLanguageLevel(
                "8",
                processorOptions,
                Collections.singletonList(processor),
                sources);
    }

    private static CompilationResult compileWithLanguageLevel(
            String languageLevel,
            List<String> processorOptions,
            List<Processor> additionalProcessors,
            Source... sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assertions.assertNotNull(compiler, "Tests must run on a JDK, not a JRE");

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<JavaFileObject>();
        Path outputDirectory;
        try {
            outputDirectory = Files.createTempDirectory("thrift-annotation-lint-compiler-test-");
        }
        catch (IOException e) {
            throw new AssertionError("Could not create compiler test directory", e);
        }

        StandardJavaFileManager fileManager = compiler.getStandardFileManager(collector, Locale.ENGLISH, StandardCharsets.UTF_8);
        try {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(outputDirectory.toFile()));
            fileManager.setLocation(StandardLocation.SOURCE_OUTPUT, Collections.singletonList(outputDirectory.toFile()));
            setClasspath(fileManager, testClasspath());
            List<String> options = new ArrayList<String>();
            options.addAll(Arrays.asList(
                    "-source", languageLevel,
                    "-target", languageLevel,
                    "-Xlint:-options",
                    "-proc:only"));
            options.addAll(processorOptions);

            List<JavaFileObject> compilationUnits = new ArrayList<JavaFileObject>();
            compilationUnits.addAll(Arrays.asList(sources));
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    collector,
                    options,
                    null,
                    compilationUnits);
            List<Processor> processors = new ArrayList<Processor>();
            processors.add(new ThriftAnnotationLintProcessor());
            processors.addAll(additionalProcessors);
            task.setProcessors(processors);
            boolean successful = Boolean.TRUE.equals(task.call());
            return new CompilationResult(successful, collector.getDiagnostics());
        }
        catch (IOException e) {
            throw new AssertionError("Could not configure the Java compiler", e);
        }
        finally {
            try {
                fileManager.close();
            }
            catch (IOException ignored) {
                // A failed close must not hide the compiler result.
            }
            deleteRecursively(outputDirectory);
        }
    }

    private static void deleteRecursively(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                }
                catch (IOException ignored) {
                    // Temporary test output is best-effort cleanup only.
                }
            });
        }
        catch (IOException ignored) {
            // Temporary test output is best-effort cleanup only.
        }
    }

    interface DependencyOutputMutator {
        void mutate(Path dependencyOutput) throws IOException;
    }

    /**
     * Captures javac's lazily-computed source positions before temporary generated sources are
     * deleted. Keeping the compiler Diagnostic itself would turn valid generated-source line and
     * column values into 0 after cleanup on some JDKs.
     */
    private static final class FrozenDiagnostic implements Diagnostic<JavaFileObject> {
        private final Kind kind;
        private final JavaFileObject source;
        private final long position;
        private final long startPosition;
        private final long endPosition;
        private final long lineNumber;
        private final long columnNumber;
        private final String code;
        private final String englishMessage;

        private FrozenDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic) {
            this.kind = diagnostic.getKind();
            this.source = diagnostic.getSource();
            this.position = diagnostic.getPosition();
            this.startPosition = diagnostic.getStartPosition();
            this.endPosition = diagnostic.getEndPosition();
            this.lineNumber = diagnostic.getLineNumber();
            this.columnNumber = diagnostic.getColumnNumber();
            this.code = diagnostic.getCode();
            this.englishMessage = diagnostic.getMessage(Locale.ENGLISH);
        }

        @Override
        public Kind getKind() {
            return kind;
        }

        @Override
        public JavaFileObject getSource() {
            return source;
        }

        @Override
        public long getPosition() {
            return position;
        }

        @Override
        public long getStartPosition() {
            return startPosition;
        }

        @Override
        public long getEndPosition() {
            return endPosition;
        }

        @Override
        public long getLineNumber() {
            return lineNumber;
        }

        @Override
        public long getColumnNumber() {
            return columnNumber;
        }

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public String getMessage(Locale locale) {
            return englishMessage;
        }
    }

    static final class CompilationResult {
        private final boolean successful;
        private final List<Diagnostic<? extends JavaFileObject>> diagnostics;

        CompilationResult(boolean successful, List<Diagnostic<? extends JavaFileObject>> diagnostics) {
            this.successful = successful;
            List<Diagnostic<? extends JavaFileObject>> frozen =
                    new ArrayList<Diagnostic<? extends JavaFileObject>>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
                frozen.add(new FrozenDiagnostic(diagnostic));
            }
            this.diagnostics = Collections.unmodifiableList(frozen);
        }

        boolean isSuccessful() {
            return successful;
        }

        List<Diagnostic<? extends JavaFileObject>> thriftAnnotationLintDiagnostics() {
            List<Diagnostic<? extends JavaFileObject>> result = new ArrayList<Diagnostic<? extends JavaFileObject>>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
                if (message(diagnostic).startsWith("[AW")) {
                    result.add(diagnostic);
                }
            }
            return result;
        }

        Diagnostic<? extends JavaFileObject> diagnostic(String code) {
            String prefix = "[" + code + "]";
            for (Diagnostic<? extends JavaFileObject> diagnostic : thriftAnnotationLintDiagnostics()) {
                if (message(diagnostic).startsWith(prefix)) {
                    return diagnostic;
                }
            }
            Assertions.fail("Expected diagnostic " + code + " but got:\n" + diagnosticSummary());
            return null;
        }

        boolean hasCode(String code) {
            String prefix = "[" + code + "]";
            for (Diagnostic<? extends JavaFileObject> diagnostic : thriftAnnotationLintDiagnostics()) {
                if (message(diagnostic).startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        String diagnosticSummary() {
            StringBuilder builder = new StringBuilder();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(diagnostic.getKind())
                        .append(" at line ")
                        .append(diagnostic.getLineNumber())
                        .append(": ")
                        .append(message(diagnostic));
            }
            return builder.toString();
        }

        void assertSucceeded() {
            Assertions.assertTrue(successful, "Compilation should succeed:\n" + diagnosticSummary());
            assertDiagnosticContract();
        }

        void assertFailedWith(String code) {
            Assertions.assertFalse(successful, "Compilation should fail with " + code + ":\n" + diagnosticSummary());
            diagnostic(code);
            assertDiagnosticContract();
        }

        void assertNoThriftAnnotationLintDiagnostics() {
            Assertions.assertTrue(thriftAnnotationLintDiagnostics().isEmpty(),
                    "Expected no ThriftAnnotationLint diagnostics:\n" + diagnosticSummary());
        }

        void assertDiagnosticContract() {
            for (Diagnostic<? extends JavaFileObject> diagnostic : thriftAnnotationLintDiagnostics()) {
                String message = message(diagnostic);
                Assertions.assertTrue(RULE_PREFIX.matcher(message).matches(),
                        "Diagnostic must begin with a stable rule code: " + message);
                Assertions.assertFalse(CJK_CHARACTER.matcher(message).find(),
                        "Diagnostic must be written in English: " + message);
                if (!message.startsWith("[AW9")) {
                    Assertions.assertTrue(diagnostic.getLineNumber() >= 1,
                            "Model diagnostic must point to a positive source line: " + message);
                }
            }
        }

        private static String message(Diagnostic<? extends JavaFileObject> diagnostic) {
            return diagnostic.getMessage(Locale.ENGLISH);
        }
    }

    static final class Source extends SimpleJavaFileObject {
        private final String content;

        Source(String className, String content) {
            super(URI.create("string:///" + className.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
                    JavaFileObject.Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }
}
