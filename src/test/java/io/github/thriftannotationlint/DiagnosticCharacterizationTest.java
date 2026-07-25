package io.github.thriftannotationlint;

import org.junit.jupiter.api.Test;

import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.List;
import java.util.Locale;
import java.util.Collections;

import static io.github.thriftannotationlint.CompilerTestSupport.compile;
import static io.github.thriftannotationlint.CompilerTestSupport.compileWithOptions;
import static io.github.thriftannotationlint.CompilerTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the complete externally visible shape of representative diagnostics. */
class DiagnosticCharacterizationTest {
    @Test
    void invalidBudgetOptionUsesTheStableAlwaysErrorDiagnostic() {
        CompilerTestSupport.CompilationResult result = compileWithOptions(
                Collections.singletonList("-Athrift.annotation.lint.maxExactModels=0"),
                source(
                        "example.ValidOptionAnchor",
                        "package example;",
                        "public class ValidOptionAnchor {}"));

        result.assertFailedWith("AW9001");
        Diagnostic<? extends JavaFileObject> diagnostic = result.diagnostic("AW9001");
        assertEquals(Diagnostic.Kind.ERROR, diagnostic.getKind());
        assertEquals(
                "[AW9001] Unsupported -Athrift.annotation.lint.maxExactModels value; expected a positive "
                        + "integer.",
                diagnostic.getMessage(Locale.ENGLISH));
    }

    @Test
    void preservesCodeSeverityMessageOrderLineAndAnnotationValueAnchor() {
        CompilerTestSupport.CompilationResult result = compile(source(
                "example.StableDiagnostics",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class StableDiagnostics {",
                "  @ThriftField(1) public String alpha;",
                "  @ThriftField(1) public Integer beta;",
                "  @ThriftField(2) public Object gamma;",
                "}"));

        List<Diagnostic<? extends JavaFileObject>> diagnostics =
                result.thriftAnnotationLintDiagnostics();
        assertEquals(2, diagnostics.size(), result.diagnosticSummary());

        Diagnostic<? extends JavaFileObject> duplicate = diagnostics.get(0);
        assertEquals(Diagnostic.Kind.ERROR, duplicate.getKind());
        assertEquals(7, duplicate.getLineNumber());
        // javac 8 reports the annotation start for the annotation-value Messager overload;
        // newer javac versions report the value itself. Both come from the same stable anchor.
        long expectedColumn = SourceVersion.latestSupported() == SourceVersion.RELEASE_8
                ? 3
                : 16;
        assertEquals(expectedColumn, duplicate.getColumnNumber());
        assertTrue(duplicate.getSource().toUri().toString().endsWith(
                "/example/StableDiagnostics.java"));
        assertEquals(
                "[AW2002] Thrift model 'example.StableDiagnostics' uses field ID 1 "
                        + "for different logical fields [alpha, beta].",
                duplicate.getMessage(Locale.ENGLISH));

        Diagnostic<? extends JavaFileObject> unsupported = diagnostics.get(1);
        assertEquals(Diagnostic.Kind.ERROR, unsupported.getKind());
        assertEquals(8, unsupported.getLineNumber());
        assertEquals(
                "[AW4001] Thrift model 'example.StableDiagnostics' field 'gamma' "
                        + "uses unsupported Java type 'java.lang.Object'.",
                unsupported.getMessage(Locale.ENGLISH));
    }
}
