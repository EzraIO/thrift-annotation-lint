package io.github.thriftannotationlint;

import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.github.thriftannotationlint.CompilerTestSupport.compile;
import static io.github.thriftannotationlint.CompilerTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Large bounded graphs guard iterative and deterministic closure behavior without timing limits. */
class ArchitectureStressTest {
    private static final int MODEL_COUNT = 192;

    @Test
    void validatesALargeFiniteDemandClosure() {
        List<String> lines = sourcePrefix("LargeFiniteClosure");
        for (int index = 0; index < MODEL_COUNT; index++) {
            lines.add("  @ThriftStruct public static class N" + index + " {");
            String target = index + 1 < MODEL_COUNT
                    ? "N" + (index + 1)
                    : "String";
            lines.add("    @ThriftField(1) public " + target + " next;");
            lines.add("  }");
        }
        lines.add("}");

        CompilerTestSupport.CompilationResult result = compile(source(
                "example.LargeFiniteClosure",
                lines.toArray(new String[lines.size()])));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void detectsOneDeterministicFindingForALargeIterativeCycle() {
        List<String> lines = sourcePrefix("LargeIterativeCycle");
        for (int index = 0; index < MODEL_COUNT; index++) {
            lines.add("  @ThriftStruct public static class N" + index + " {");
            String target = "N" + ((index + 1) % MODEL_COUNT);
            lines.add("    @ThriftField(1) public " + target + " next;");
            lines.add("  }");
        }
        lines.add("}");

        CompilerTestSupport.CompilationResult result = compile(source(
                "example.LargeIterativeCycle",
                lines.toArray(new String[lines.size()])));

        result.assertFailedWith("AW4003");
        int cycleFindings = 0;
        for (Diagnostic<? extends JavaFileObject> diagnostic
                : result.thriftAnnotationLintDiagnostics()) {
            if (diagnostic.getMessage(Locale.ENGLISH).startsWith("[AW4003]")) {
                cycleFindings++;
            }
        }
        assertEquals(1, cycleFindings, result.diagnosticSummary());
    }

    private List<String> sourcePrefix(String outerName) {
        List<String> lines = new ArrayList<String>();
        lines.add("package example;");
        lines.add("import com.facebook.swift.codec.ThriftField;");
        lines.add("import com.facebook.swift.codec.ThriftStruct;");
        lines.add("public class " + outerName + " {");
        return lines;
    }
}
