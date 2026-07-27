package io.github.thriftannotationlint;

import org.junit.jupiter.api.Test;

import static io.github.thriftannotationlint.CompilerTestSupport.compile;
import static io.github.thriftannotationlint.CompilerTestSupport.compileAgainstClasspath;
import static io.github.thriftannotationlint.CompilerTestSupport.compileAgainstClasspathWithoutDebug;
import static io.github.thriftannotationlint.CompilerTestSupport.source;

final class DriftCompatibilityTest {
    @Test
    void acceptsAirliftDriftStructFieldsAndAccessors() {
        CompilerTestSupport.CompilationResult result = compile(source("example.DriftPerson",
                "package example;",
                "import io.airlift.drift.annotations.ThriftField;",
                "import io.airlift.drift.annotations.ThriftStruct;",
                "@ThriftStruct",
                "public class DriftPerson {",
                "  private String name;",
                "  @ThriftField(1) public String getName() { return name; }",
                "  @ThriftField(1) public void setName(String name) { this.name = name; }",
                "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void reportsDuplicateDriftFieldIds() {
        CompilerTestSupport.CompilationResult result = compile(source("example.BadDriftStruct",
                "package example;",
                "import io.airlift.drift.annotations.ThriftField;",
                "import io.airlift.drift.annotations.ThriftStruct;",
                "@ThriftStruct",
                "public class BadDriftStruct {",
                "  @ThriftField(7) public String first;",
                "  @ThriftField(7) public String second;",
                "}"));

        result.assertFailedWith("AW2002");
    }

    @Test
    void acceptsDriftConstructorWithExplicitParameterIdentity() {
        CompilerTestSupport.CompilationResult result = compile(source("example.ImmutableDriftStruct",
                "package example;",
                "import io.airlift.drift.annotations.ThriftConstructor;",
                "import io.airlift.drift.annotations.ThriftField;",
                "import io.airlift.drift.annotations.ThriftStruct;",
                "@ThriftStruct",
                "public class ImmutableDriftStruct {",
                "  private final String value;",
                "  @ThriftConstructor public ImmutableDriftStruct(",
                "      @ThriftField(value=1, name=\"value\") String value) { this.value = value; }",
                "  @ThriftField(1) public String getValue() { return value; }",
                "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void rejectsDriftConstructorWhoseRuntimeParameterIdentityIsUnstable() {
        CompilerTestSupport.CompilationResult result = compile(source("example.UnstableDriftStruct",
                "package example;",
                "import io.airlift.drift.annotations.ThriftConstructor;",
                "import io.airlift.drift.annotations.ThriftField;",
                "import io.airlift.drift.annotations.ThriftStruct;",
                "@ThriftStruct",
                "public class UnstableDriftStruct {",
                "  @ThriftConstructor public UnstableDriftStruct(@ThriftField String value) {}",
                "  @ThriftField(1) public String getValue() { return null; }",
                "}"));

        result.assertFailedWith("AW3003");
    }

    @Test
    void acceptsDriftUnionAndEnumMetadata() {
        CompilerTestSupport.CompilationResult result = compile(
                source("example.DriftChoice",
                        "package example;",
                        "import io.airlift.drift.annotations.*;",
                        "@ThriftUnion public class DriftChoice {",
                        "  @ThriftUnionId public short id;",
                        "  @ThriftField(1) public String text;",
                        "}"),
                source("example.DriftState",
                        "package example;",
                        "import io.airlift.drift.annotations.*;",
                        "@ThriftEnum public enum DriftState {",
                        "  READY(1), DONE(2);",
                        "  private final int value;",
                        "  DriftState(int value) { this.value = value; }",
                        "  @ThriftEnumValue public int getValue() { return value; }",
                        "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void rejectsMixingSwiftFieldsIntoADriftModel() {
        CompilerTestSupport.CompilationResult result = compile(source("example.MixedDialect",
                "package example;",
                "@io.airlift.drift.annotations.ThriftStruct",
                "public class MixedDialect {",
                "  @com.facebook.swift.codec.ThriftField(1) public String value;",
                "}"));

        result.assertFailedWith("AW1001");
    }

    @Test
    void reproducesDriftBytecodeParameterNameLookupForClasspathModels() {
        CompilerTestSupport.Source dependency = source("example.DriftDependency",
                "package example;",
                "import io.airlift.drift.annotations.*;",
                "@ThriftStruct public class DriftDependency {",
                "  private final String value;",
                "  @ThriftConstructor public DriftDependency(@ThriftField String value) {",
                "    this.value = value;",
                "  }",
                "  @ThriftField(1) public String getValue() { return value; }",
                "}");
        CompilerTestSupport.Source root = source("example.DriftRoot",
                "package example;",
                "import io.airlift.drift.annotations.*;",
                "@ThriftStruct public class DriftRoot {",
                "  @ThriftField(1) public DriftDependency dependency;",
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
    void acceptsOneDriftUnknownEnumFallback() {
        CompilerTestSupport.CompilationResult result = compile(source("example.ForwardCompatibleState",
                "package example;",
                "import io.airlift.drift.annotations.*;",
                "@ThriftEnum public enum ForwardCompatibleState {",
                "  READY(1),",
                "  @ThriftEnumUnknownValue UNKNOWN(-1);",
                "  private final int value;",
                "  ForwardCompatibleState(int value) { this.value = value; }",
                "  @ThriftEnumValue public int getValue() { return value; }",
                "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void rejectsMultipleDriftUnknownEnumFallbacks() {
        CompilerTestSupport.CompilationResult result = compile(source("example.AmbiguousUnknownState",
                "package example;",
                "import io.airlift.drift.annotations.*;",
                "public enum AmbiguousUnknownState {",
                "  @ThriftEnumUnknownValue FIRST,",
                "  @ThriftEnumUnknownValue SECOND;",
                "}"));

        result.assertFailedWith("AW6002");
    }

    @Test
    void requiresExactlyOneDriftEnumValueMethod() {
        CompilerTestSupport.CompilationResult result = compile(source("example.MissingValue",
                "package example;",
                "import io.airlift.drift.annotations.ThriftEnum;",
                "@ThriftEnum public enum MissingValue { READY }"));

        result.assertFailedWith("AW6001");
    }

    @Test
    void acceptsDriftOptionalWireTypesAndNestedModels() {
        CompilerTestSupport.CompilationResult result = compile(
                source("example.OptionalChild",
                        "package example;",
                        "import io.airlift.drift.annotations.*;",
                        "@ThriftStruct public class OptionalChild {",
                        "  @ThriftField(1) public String value;",
                        "}"),
                source("example.OptionalModel",
                        "package example;",
                        "import io.airlift.drift.annotations.*;",
                        "import java.util.*;",
                        "@ThriftStruct public class OptionalModel {",
                        "  @ThriftField(1) public Optional<String> text;",
                        "  @ThriftField(2) public OptionalInt count;",
                        "  @ThriftField(3) public OptionalLong sequence;",
                        "  @ThriftField(4) public OptionalDouble ratio;",
                        "  @ThriftField(5) public Optional<List<OptionalChild>> children;",
                        "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void rejectsRawAndUnsupportedDriftOptionalElements() {
        CompilerTestSupport.CompilationResult raw = compile(source("example.RawOptional",
                "package example;",
                "import io.airlift.drift.annotations.*;",
                "@ThriftStruct public class RawOptional {",
                "  @ThriftField(1) public java.util.Optional value;",
                "}"));
        CompilerTestSupport.CompilationResult unsupported = compile(source("example.BadOptional",
                "package example;",
                "import io.airlift.drift.annotations.*;",
                "@ThriftStruct public class BadOptional {",
                "  @ThriftField(1) public java.util.Optional<Object> value;",
                "}"));

        raw.assertFailedWith("AW4001");
        unsupported.assertFailedWith("AW4001");
    }

    @Test
    void rejectsMismatchedOptionalCarrierShapesWithinOneLogicalField() {
        CompilerTestSupport.CompilationResult generic = compile(source("example.MixedOptional",
                "package example;",
                "import io.airlift.drift.annotations.*;",
                "@ThriftStruct public class MixedOptional {",
                "  @ThriftField(1) public java.util.Optional<String> getValue() { return null; }",
                "  @ThriftField(1) public void setValue(String value) {}",
                "}"));
        CompilerTestSupport.CompilationResult primitive = compile(source("example.MixedOptionalInt",
                "package example;",
                "import io.airlift.drift.annotations.*;",
                "@ThriftStruct public class MixedOptionalInt {",
                "  @ThriftField(1) public java.util.OptionalInt getValue() { return null; }",
                "  @ThriftField(1) public void setValue(Integer value) {}",
                "}"));

        generic.assertFailedWith("AW4002");
        primitive.assertFailedWith("AW4002");
    }

    @Test
    void checksCanonicalContainerDirectionsInsideDriftOptional() {
        CompilerTestSupport.CompilationResult result = compile(source("example.OptionalArrayList",
                "package example;",
                "import io.airlift.drift.annotations.*;",
                "@ThriftStruct public class OptionalArrayList {",
                "  @ThriftField(1) public java.util.Optional<java.util.ArrayList<String>> value;",
                "}"));

        result.assertFailedWith("AW4002");
    }

    @Test
    void keepsOptionalUnsupportedForSwift() {
        CompilerTestSupport.CompilationResult result = compile(source("example.SwiftOptional",
                "package example;",
                "import com.facebook.swift.codec.*;",
                "@ThriftStruct public class SwiftOptional {",
                "  @ThriftField(1) public java.util.Optional<String> value;",
                "}"));

        result.assertFailedWith("AW4001");
    }

    @Test
    void rejectsExplicitCrossDialectModelReferences() {
        CompilerTestSupport.CompilationResult result = compile(
                source("example.SwiftChild",
                        "package example;",
                        "@com.facebook.swift.codec.ThriftStruct",
                        "public class SwiftChild {}"),
                source("example.DriftParent",
                        "package example;",
                        "@io.airlift.drift.annotations.ThriftStruct",
                        "public class DriftParent {",
                        "  @io.airlift.drift.annotations.ThriftField(1)",
                        "  public SwiftChild child;",
                        "}"));

        result.assertFailedWith("AW1001");
        org.junit.jupiter.api.Assertions.assertFalse(
                result.hasCode("AW4001"), result.diagnosticSummary());
    }

    @Test
    void plainEnumInheritsTheReferencingDriftDialect() {
        CompilerTestSupport.CompilationResult result = compile(
                source("example.PlainState",
                        "package example;",
                        "public enum PlainState { READY }"),
                source("example.DriftEnumOwner",
                        "package example;",
                        "@io.airlift.drift.annotations.ThriftStruct",
                        "public class DriftEnumOwner {",
                        "  @io.airlift.drift.annotations.ThriftField(1)",
                        "  public PlainState state;",
                        "}"));

        result.assertFailedWith("AW6001");
    }

    @Test
    void validatesOnePlainEnumIndependentlyForBothDialects() {
        CompilerTestSupport.CompilationResult result = compile(
                source("example.SharedState",
                        "package example;",
                        "public enum SharedState {",
                        "  READY;",
                        "  @io.airlift.drift.annotations.ThriftEnumValue",
                        "  public int value() { return ordinal(); }",
                        "}"),
                source("example.SwiftEnumOwner",
                        "package example;",
                        "@com.facebook.swift.codec.ThriftStruct",
                        "public class SwiftEnumOwner {",
                        "  @com.facebook.swift.codec.ThriftField(1) public SharedState state;",
                        "}"),
                source("example.DriftSharedEnumOwner",
                        "package example;",
                        "@io.airlift.drift.annotations.ThriftStruct",
                        "public class DriftSharedEnumOwner {",
                        "  @io.airlift.drift.annotations.ThriftField(1) public SharedState state;",
                        "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void validatesStandalonePlainEnumMetadataForBothDialects() {
        CompilerTestSupport.CompilationResult result = compile(source("example.DualMetadataState",
                "package example;",
                "public enum DualMetadataState {",
                "  READY;",
                "  @com.facebook.swift.codec.ThriftEnumValue",
                "  public int swiftValue() { return ordinal(); }",
                "  @io.airlift.drift.annotations.ThriftEnumValue",
                "  public String driftValue() { return name(); }",
                "}"));

        result.assertFailedWith("AW6001");
    }
}
