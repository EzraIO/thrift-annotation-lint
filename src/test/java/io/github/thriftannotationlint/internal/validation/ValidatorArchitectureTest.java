package io.github.thriftannotationlint.internal.validation;

import io.github.thriftannotationlint.internal.model.FieldPart;
import io.github.thriftannotationlint.internal.model.ResolvedLogicalFields;
import io.github.thriftannotationlint.internal.model.ThriftFieldData;

import org.junit.jupiter.api.Test;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.github.thriftannotationlint.CompilerTestSupport.CompilationResult;
import static io.github.thriftannotationlint.CompilerTestSupport.compile;
import static io.github.thriftannotationlint.CompilerTestSupport.compileWithAdditionalProcessor;
import static io.github.thriftannotationlint.CompilerTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValidatorArchitectureTest {
    @Test
    void logicalFieldResolutionUsesExactlyTwoNamePassesAndIsImmutable() {
        LogicalFieldProbe probe = new LogicalFieldProbe();
        CompilationResult compilation = compileWithAdditionalProcessor(
                probe,
                source("example.ResolverFixture",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "public class ResolverFixture {",
                        "  @ThriftField(value=7, name=\"x\") public String a;",
                        "  @ThriftField(name=\"x\") public String b;",
                        "  @ThriftField(name=\"z\") public String c;",
                        "  @ThriftField(name=\"z\") public String d;",
                        "}"));

        compilation.assertSucceeded();
        assertNotNull(probe.resolvedFields);
        assertEquals(1, probe.resolvedFields.fields().size());
        assertEquals(4, probe.resolvedFields.fields().get(0).parts().size());
        ResolvedLogicalFields.IdResolution ids = probe.resolvedFields.idResolution();
        assertEquals(Short.valueOf((short) 7), ids.id(probe.partsByName.get("a")));
        assertEquals(Short.valueOf((short) 7), ids.id(probe.partsByName.get("b")));
        assertEquals(Short.valueOf((short) 7), ids.id(probe.partsByName.get("c")));
        assertNull(ids.id(probe.partsByName.get("d")),
                "The extracted-name result must not flow back through the first pass");
        assertThrows(UnsupportedOperationException.class,
                () -> probe.resolvedFields.fields().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> probe.resolvedFields.fields().get(0).parts().clear());
    }

    @Test
    void unionRulesKeepTheirExactMessagesLocationsAndOrder() {
        CompilationResult result = compile(source("example.UnionRules",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftUnion;",
                "import com.facebook.swift.codec.ThriftUnionId;",
                "import static com.facebook.swift.codec.ThriftField.Requiredness.REQUIRED;",
                "@ThriftUnion",
                "public class UnionRules {",
                "  @ThriftUnionId public int id;",
                "  @ThriftField(value=0, requiredness=REQUIRED) public String payload;",
                "}"));

        result.assertFailedWith("AW5001");
        List<Diagnostic<? extends JavaFileObject>> diagnostics = result.thriftAnnotationLintDiagnostics();
        assertEquals(3, diagnostics.size(), result.diagnosticSummary());
        assertDiagnostic(
                diagnostics.get(0),
                8,
                "[AW5001] @ThriftUnionId member in union 'example.UnionRules' must use "
                        + "primitive short for Swift's default compiler codec, but found 'int'.");
        assertDiagnostic(
                diagnostics.get(1),
                9,
                "[AW5003] Thrift union 'example.UnionRules' field 'payload' must not be marked "
                        + "required or optional.");
        assertDiagnostic(
                diagnostics.get(2),
                9,
                "[AW5004] Thrift union 'example.UnionRules' field 'payload' uses ID 0, which "
                        + "collides with the default compiler codec's initial no-field "
                        + "discriminator during decoding.");
    }

    @Test
    void noLvtFindingRelocationRemainsDeduplicatedAndExact() {
        CompilationResult result = compile(source("example.FallbackUnionName",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftUnion;",
                "import com.facebook.swift.codec.ThriftUnionId;",
                "@ThriftUnion",
                "public class FallbackUnionName {",
                "  @ThriftUnionId public short arg0;",
                "  @ThriftConstructor",
                "  public FallbackUnionName(@ThriftField(1) String payload) {}",
                "  @ThriftField(value=1, name=\"payload\")",
                "  public String getPayload() { return \"\"; }",
                "}"));

        result.assertFailedWith("AW2003");
        assertEquals(1, result.thriftAnnotationLintDiagnostics().size(), result.diagnosticSummary());
        assertEquals(
                "[AW2003] If emitted bytecode omits LocalVariableTable parameter names: "
                        + "Thrift union 'example.FallbackUnionName' field 'payload' collides with "
                        + "Swift's internal union discriminator during extracted-name ID inference.",
                result.thriftAnnotationLintDiagnostics().get(0).getMessage(Locale.ENGLISH));
    }

    @Test
    void recursiveCycleRepresentativeRemainsDeterministic() {
        CompilationResult result = compile(
                source("example.CycleA",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class CycleA {",
                        "  @ThriftField(1) public CycleB b;",
                        "}"),
                source("example.CycleB",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class CycleB {",
                        "  @ThriftField(1) public CycleA a;",
                        "}"));

        result.assertFailedWith("AW4003");
        assertEquals(1, result.thriftAnnotationLintDiagnostics().size(), result.diagnosticSummary());
        assertEquals(
                "[AW4003] Unqualified recursive cycle detected: 'example.CycleB.a -> "
                        + "example.CycleA.b -> example.CycleB'. Mark at least one direct edge in "
                        + "this cycle with isRecursive=TRUE.",
                result.thriftAnnotationLintDiagnostics().get(0).getMessage(Locale.ENGLISH));
    }

    private static void assertDiagnostic(
            Diagnostic<? extends JavaFileObject> diagnostic,
            long expectedLine,
            String expectedMessage) {
        assertEquals(Diagnostic.Kind.ERROR, diagnostic.getKind());
        assertEquals(expectedLine, diagnostic.getLineNumber());
        assertEquals(expectedMessage, diagnostic.getMessage(Locale.ENGLISH));
    }

    @SupportedAnnotationTypes("*")
    private static final class LogicalFieldProbe extends AbstractProcessor {
        private final Map<String, FieldPart> partsByName =
                new LinkedHashMap<String, FieldPart>();
        private ResolvedLogicalFields resolvedFields;

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.RELEASE_8;
        }

        @Override
        public boolean process(
                Set<? extends TypeElement> annotations,
                RoundEnvironment roundEnvironment) {
            if (resolvedFields != null || roundEnvironment.processingOver()) {
                return false;
            }
            TypeElement fixture = processingEnv.getElementUtils()
                    .getTypeElement("example.ResolverFixture");
            if (fixture == null) {
                return false;
            }

            List<FieldPart> parts = new ArrayList<FieldPart>();
            for (Element enclosed : fixture.getEnclosedElements()) {
                if (!(enclosed instanceof VariableElement)) {
                    continue;
                }
                VariableElement field = (VariableElement) enclosed;
                String sourceName = field.getSimpleName().toString();
                String extractedName;
                if ("b".equals(sourceName) || "c".equals(sourceName)) {
                    extractedName = "y";
                }
                else {
                    extractedName = sourceName;
                }
                FieldPart part = new FieldPart(
                        FieldPart.Source.FIELD,
                        field,
                        field,
                        extractedName,
                        field.asType(),
                        ThriftFieldData.from(processingEnv.getElementUtils(), field),
                        true,
                        true);
                parts.add(part);
                partsByName.put(sourceName, part);
            }
            resolvedFields = new LogicalFieldResolver().resolve(parts);
            return false;
        }
    }
}
