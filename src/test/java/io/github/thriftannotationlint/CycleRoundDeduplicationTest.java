package io.github.thriftannotationlint;

import org.junit.jupiter.api.Test;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

import static io.github.thriftannotationlint.CompilerTestSupport.compileWithOptionsAndAdditionalProcessor;
import static io.github.thriftannotationlint.CompilerTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A rebuilt SCC may rotate its representative edge, but it is still one semantic finding. */
class CycleRoundDeduplicationTest {
    @Test
    void reportsOneCycleWhenAnUnrelatedThirdRoundRotatesTheRepresentativeEdge() {
        CompilerTestSupport.CompilationResult result =
                compileWithOptionsAndAdditionalProcessor(
                        Collections.singletonList("-Athrift.annotation.lint.mode=warning"),
                        new ChainedRoundCycleGenerator(),
                        source(
                                "example.RoundA",
                                "package example;",
                                "import com.facebook.swift.codec.ThriftField;",
                                "import com.facebook.swift.codec.ThriftStruct;",
                                "@ThriftStruct",
                                "public class RoundA {",
                                "  @ThriftField(1) public RoundB value;",
                                "}"));

        result.assertSucceeded();
        int cycleFindings = 0;
        Diagnostic<? extends JavaFileObject> cycleFinding = null;
        for (Diagnostic<? extends JavaFileObject> diagnostic
                : result.thriftAnnotationLintDiagnostics()) {
            if (diagnostic.getMessage(Locale.ENGLISH).startsWith("[AW4003]")) {
                cycleFindings++;
                cycleFinding = diagnostic;
            }
        }
        assertEquals(1, cycleFindings, result.diagnosticSummary());
        assertNotNull(cycleFinding);
        assertEquals(Diagnostic.Kind.WARNING, cycleFinding.getKind());
        assertNotNull(cycleFinding.getSource());
        assertTrue(cycleFinding.getSource().getName().replace('\\', '/')
                        .endsWith("/example/RoundB.java"),
                cycleFinding.getSource().getName());
        assertEquals(4, cycleFinding.getLineNumber());
        assertTrue(cycleFinding.getColumnNumber() >= 1);
        assertEquals(
                "[AW4003] While validating referenced Thrift models: Unqualified recursive cycle "
                        + "detected: 'example.RoundA.value -> example.RoundB.value -> "
                        + "example.RoundA'. Mark at least one direct edge in this cycle with "
                        + "isRecursive=TRUE.",
                cycleFinding.getMessage(Locale.ENGLISH));
    }

    private static final class ChainedRoundCycleGenerator extends AbstractProcessor {
        private int generatedSources;

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Collections.singleton("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(
                Set<? extends TypeElement> annotations,
                RoundEnvironment roundEnvironment) {
            if (roundEnvironment.processingOver() || generatedSources >= 2) {
                return false;
            }
            String className;
            String sourceText;
            if (generatedSources++ == 0) {
                className = "example.RoundB";
                sourceText = "package example;\n"
                        + "import com.facebook.swift.codec.ThriftField;\n"
                        + "import com.facebook.swift.codec.ThriftStruct;\n"
                        + "@ThriftStruct public class RoundB {\n"
                        + "  @ThriftField(1) public RoundA value;\n"
                        + "}\n";
            }
            else {
                className = "example.RoundTrigger";
                sourceText = "package example; public class RoundTrigger {}\n";
            }
            try {
                Writer writer = processingEnv.getFiler()
                        .createSourceFile(className)
                        .openWriter();
                try {
                    writer.write(sourceText);
                }
                finally {
                    writer.close();
                }
            }
            catch (IOException failure) {
                throw new IllegalStateException("Could not generate cycle-round fixture", failure);
            }
            return false;
        }
    }
}
