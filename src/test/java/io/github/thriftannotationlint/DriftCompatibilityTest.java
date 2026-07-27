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
}
