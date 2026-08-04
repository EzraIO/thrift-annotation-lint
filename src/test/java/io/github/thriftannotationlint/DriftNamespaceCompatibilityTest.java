package io.github.thriftannotationlint;

import org.junit.jupiter.api.Test;

import static io.github.thriftannotationlint.CompilerTestSupport.compile;
import static io.github.thriftannotationlint.CompilerTestSupport.compileAgainstClasspath;
import static io.github.thriftannotationlint.CompilerTestSupport.compileAgainstClasspathWithoutDebug;
import static io.github.thriftannotationlint.CompilerTestSupport.source;

/** Verifies PrestoDB Drift support without collapsing its namespace into Airlift Drift. */
final class DriftNamespaceCompatibilityTest {
    @Test
    void reportsDuplicatePrestoDriftFieldIds() {
        CompilerTestSupport.CompilationResult result = compile(source("example.PrestoDriftDuplicateIds",
                "package example;",
                "import com.facebook.drift.annotations.ThriftField;",
                "import com.facebook.drift.annotations.ThriftStruct;",
                "@ThriftStruct",
                "public class PrestoDriftDuplicateIds {",
                "  @ThriftField(7) public String first;",
                "  @ThriftField(7) public String second;",
                "}"));

        result.assertFailedWith("AW2002");
    }

    @Test
    void acceptsPrestoDriftConstructorWithExplicitParameterIdentity() {
        CompilerTestSupport.CompilationResult result = compile(source("example.PrestoDriftValue",
                "package example;",
                "import com.facebook.drift.annotations.ThriftConstructor;",
                "import com.facebook.drift.annotations.ThriftField;",
                "import com.facebook.drift.annotations.ThriftStruct;",
                "@ThriftStruct",
                "public class PrestoDriftValue {",
                "  private final String value;",
                "  @ThriftConstructor public PrestoDriftValue(",
                "      @ThriftField(value=1, name=\"value\") String value) { this.value = value; }",
                "  @ThriftField(1) public String getValue() { return value; }",
                "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void acceptsPrestoDriftUnionEnumOptionalAndNestedModels() {
        CompilerTestSupport.CompilationResult result = compile(
                source("example.PrestoDriftChild",
                        "package example;",
                        "import com.facebook.drift.annotations.*;",
                        "@ThriftStruct public class PrestoDriftChild {",
                        "  @ThriftField(1) public String value;",
                        "}"),
                source("example.PrestoDriftContainer",
                        "package example;",
                        "import com.facebook.drift.annotations.*;",
                        "import java.util.Optional;",
                        "@ThriftStruct public class PrestoDriftContainer {",
                        "  @ThriftField(1) public Optional<PrestoDriftChild> child;",
                        "}"),
                source("example.PrestoDriftChoice",
                        "package example;",
                        "import com.facebook.drift.annotations.*;",
                        "@ThriftUnion public class PrestoDriftChoice {",
                        "  @ThriftUnionId public short id;",
                        "  @ThriftField(1) public String text;",
                        "}"),
                source("example.PrestoDriftState",
                        "package example;",
                        "import com.facebook.drift.annotations.*;",
                        "@ThriftEnum public enum PrestoDriftState {",
                        "  READY(1),",
                        "  @ThriftEnumUnknownValue UNKNOWN(-1);",
                        "  private final int value;",
                        "  PrestoDriftState(int value) { this.value = value; }",
                        "  @ThriftEnumValue public int getValue() { return value; }",
                        "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void validatesPrestoDriftModelsLoadedFromTheClasspath() {
        CompilerTestSupport.Source dependency = source("example.PrestoDriftDependency",
                "package example;",
                "import com.facebook.drift.annotations.*;",
                "@ThriftStruct public class PrestoDriftDependency {",
                "  private final String value;",
                "  @ThriftConstructor public PrestoDriftDependency(@ThriftField String value) {",
                "    this.value = value;",
                "  }",
                "  @ThriftField(1) public String getValue() { return value; }",
                "}");
        CompilerTestSupport.Source root = source("example.PrestoDriftRoot",
                "package example;",
                "import com.facebook.drift.annotations.*;",
                "@ThriftStruct public class PrestoDriftRoot {",
                "  @ThriftField(1) public PrestoDriftDependency dependency;",
                "}");

        CompilerTestSupport.CompilationResult withDebug = compileAgainstClasspath(
                new CompilerTestSupport.Source[] {dependency}, root);
        CompilerTestSupport.CompilationResult withoutDebug =
                compileAgainstClasspathWithoutDebug(
                        new CompilerTestSupport.Source[] {dependency}, root);

        withDebug.assertSucceeded();
        withDebug.assertNoThriftAnnotationLintDiagnostics();
        withoutDebug.assertFailedWith("AW3003");
    }

    @Test
    void rejectsAirliftStructWithPrestoDriftField() {
        CompilerTestSupport.CompilationResult result = compile(source("example.MixedDriftField",
                "package example;",
                "@io.airlift.drift.annotations.ThriftStruct",
                "public class MixedDriftField {",
                "  @com.facebook.drift.annotations.ThriftField(1) public String value;",
                "}"));

        result.assertFailedWith("AW1001");
    }

    @Test
    void rejectsPrestoStructWithAirliftDriftField() {
        CompilerTestSupport.CompilationResult result = compile(source("example.ReverseMixedDriftField",
                "package example;",
                "@com.facebook.drift.annotations.ThriftStruct",
                "public class ReverseMixedDriftField {",
                "  @io.airlift.drift.annotations.ThriftField(1) public String value;",
                "}"));

        result.assertFailedWith("AW1001");
    }

    @Test
    void rejectsTwoDriftStructAnnotationsOnOneModel() {
        CompilerTestSupport.CompilationResult result = compile(source("example.DualDriftStruct",
                "package example;",
                "@io.airlift.drift.annotations.ThriftStruct",
                "@com.facebook.drift.annotations.ThriftStruct",
                "public class DualDriftStruct {",
                "  @io.airlift.drift.annotations.ThriftField(1) public String value;",
                "}"));

        result.assertFailedWith("AW1001");
    }

    @Test
    void rejectsCrossNamespaceDriftModelReferencesInBothDirections() {
        CompilerTestSupport.CompilationResult airliftToPresto = compile(
                source("example.PrestoChild",
                        "package example;",
                        "@com.facebook.drift.annotations.ThriftStruct",
                        "public class PrestoChild {}"),
                source("example.AirliftParent",
                        "package example;",
                        "@io.airlift.drift.annotations.ThriftStruct",
                        "public class AirliftParent {",
                        "  @io.airlift.drift.annotations.ThriftField(1) public PrestoChild child;",
                        "}"));
        CompilerTestSupport.CompilationResult prestoToAirlift = compile(
                source("example.AirliftChild",
                        "package example;",
                        "@io.airlift.drift.annotations.ThriftStruct",
                        "public class AirliftChild {}"),
                source("example.PrestoParent",
                        "package example;",
                        "@com.facebook.drift.annotations.ThriftStruct",
                        "public class PrestoParent {",
                        "  @com.facebook.drift.annotations.ThriftField(1) public AirliftChild child;",
                        "}"));

        airliftToPresto.assertFailedWith("AW1001");
        prestoToAirlift.assertFailedWith("AW1001");
    }
}
