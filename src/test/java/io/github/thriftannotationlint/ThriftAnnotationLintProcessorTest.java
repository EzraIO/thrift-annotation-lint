package io.github.thriftannotationlint;

import org.junit.jupiter.api.Test;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.Set;

import static io.github.thriftannotationlint.CompilerTestSupport.CompilationResult;
import static io.github.thriftannotationlint.CompilerTestSupport.Source;
import static io.github.thriftannotationlint.CompilerTestSupport.compile;
import static io.github.thriftannotationlint.CompilerTestSupport.compileWithAdditionalProcessor;
import static io.github.thriftannotationlint.CompilerTestSupport.compileWithLanguageLevel;
import static io.github.thriftannotationlint.CompilerTestSupport.compileWithOptions;
import static io.github.thriftannotationlint.CompilerTestSupport.compileWithOptionsAndAdditionalProcessor;
import static io.github.thriftannotationlint.CompilerTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ThriftAnnotationLintProcessorTest {
    @Test
    void remainsInertForOrdinarySourceWithoutSwiftMetadata() {
        CompilationResult result = compile(source("example.PlainValue",
                "package example;",
                "public class PlainValue {",
                "  public String value;",
                "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void acceptsPositionalFieldIdAndIgnoresUnannotatedHelperFields() {
        CompilationResult result = compile(source("example.Person",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class Person {",
                "  private Object runtimeCache;",
                "  private static Object sharedCache;",
                "  @ThriftField(1)",
                "  public String name;",
                "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void mergesFieldGetterAndSetterWithTheSameId() {
        CompilationResult result = compile(source("example.MergedField",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class MergedField {",
                "  @ThriftField(1)",
                "  public String name;",
                "  @ThriftField(1)",
                "  public String getName() { return name; }",
                "  @ThriftField(1)",
                "  public void setName(String name) { this.name = name; }",
                "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void rejectsReflectionOrderDependentExtractorsAndUnionSetters() {
        CompilationResult readers = compile(source("example.AmbiguousReaders",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class AmbiguousReaders {",
                "  @ThriftField(value=1, name=\"value\")",
                "  public String getFirst() { return \"first\"; }",
                "  @ThriftField(value=1, name=\"value\")",
                "  public String getSecond() { return \"second\"; }",
                "  @ThriftField(value=1, name=\"value\")",
                "  public void setValue(String value) {}",
                "}"));
        CompilationResult unionSetters = compile(source("example.AmbiguousUnionSetters",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftUnion;",
                "import com.facebook.swift.codec.ThriftUnionId;",
                "@ThriftUnion",
                "public class AmbiguousUnionSetters {",
                "  @ThriftUnionId public short id;",
                "  @ThriftField(value=1, name=\"value\")",
                "  public String getValue() { return null; }",
                "  @ThriftField(value=1, name=\"value\")",
                "  public void setFirst(String value) {}",
                "  @ThriftField(value=1, name=\"value\")",
                "  public void setSecond(String value) {}",
                "}"));

        readers.assertFailedWith("AW3003");
        unionSetters.assertFailedWith("AW3003");
    }

    @Test
    void acceptsMultipleFieldExtractorsWhenAUniqueGetterDeterministicallyWins() {
        CompilationResult result = compile(source("example.GetterOverridesFields",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class GetterOverridesFields {",
                "  @ThriftField(value=1, name=\"value\") public String first;",
                "  @ThriftField(value=1, name=\"value\") public String second;",
                "  @ThriftField(value=1, name=\"value\")",
                "  public String getValue() { return first; }",
                "  @ThriftField(value=1, name=\"value\")",
                "  public void setValue(String value) { this.first = value; }",
                "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void infersTheGetterIdForAnUnnumberedSetterOfTheSameProperty() {
        CompilationResult result = compile(source("example.InferredSetterId",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class InferredSetterId {",
                "  private String name;",
                "  @ThriftField(1)",
                "  public String getName() { return name; }",
                "  @ThriftField",
                "  public void setName(String name) { this.name = name; }",
                "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void rejectsANonPublicThriftModel() {
        CompilationResult result = compile(source("example.PackagePrivateStruct",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "class PackagePrivateStruct {",
                "  @ThriftField(1) public String value;",
                "}"));

        result.assertFailedWith("AW1001");
    }

    @Test
    void rejectsTheSameIdOnDifferentLogicalFields() {
        CompilationResult result = compile(source("example.DuplicateIds",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class DuplicateIds {",
                "  @ThriftField(7) public String first;",
                "  @ThriftField(7) public String second;",
                "}"));

        result.assertFailedWith("AW2002");
    }

    @Test
    void rejectsConflictingIdsOnOneLogicalField() {
        CompilationResult result = compile(source("example.ConflictingIds",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class ConflictingIds {",
                "  private String name;",
                "  @ThriftField(1) public String getName() { return name; }",
                "  @ThriftField(2) public void setName(String name) { this.name = name; }",
                "}"));

        result.assertFailedWith("AW2003");
        assertFalse(result.hasCode("AW2002"), result.diagnosticSummary());
    }

    @Test
    void rejectsAFieldWithoutAnId() {
        CompilationResult result = compile(source("example.MissingId",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class MissingId {",
                "  @ThriftField public String value;",
                "}"));

        result.assertFailedWith("AW2001");
    }

    @Test
    void reportsFieldMetadataConflictsWithStableCodes() {
        CompilationResult result = compile(source("example.MetadataConflicts",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftIdlAnnotation;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import static com.facebook.swift.codec.ThriftField.Requiredness.OPTIONAL;",
                "import static com.facebook.swift.codec.ThriftField.Requiredness.REQUIRED;",
                "@ThriftStruct",
                "public class MetadataConflicts {",
                "  private String value;",
                "  @ThriftField(value=1, name=\"first\", requiredness=OPTIONAL,",
                "      idlAnnotations=@ThriftIdlAnnotation(key=\"format\", value=\"a\"))",
                "  public String getValue() { return value; }",
                "  @ThriftField(value=1, name=\"second\", requiredness=REQUIRED,",
                "      idlAnnotations=@ThriftIdlAnnotation(key=\"format\", value=\"b\"))",
                "  public void setValue(String value) { this.value = value; }",
                "}"));

        assertTrue(result.hasCode("AW2004"), result.diagnosticSummary());
        assertTrue(result.hasCode("AW2005"), result.diagnosticSummary());
        assertTrue(result.hasCode("AW2007"), result.diagnosticSummary());
        result.assertDiagnosticContract();
    }

    @Test
    void validatesLegacyIds() {
        CompilationResult missingLegacyFlag = compile(source("example.InvalidLegacyId",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class InvalidLegacyId {",
                "  @ThriftField(-1) public String value;",
                "}"));
        CompilationResult inferredDefaultLegacyFlag = compile(source("example.MixedLegacyId",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class MixedLegacyId {",
                "  @ThriftField(value=-1, isLegacyId=true)",
                "  public String getValue() { return \"\"; }",
                "  @ThriftField public void setValue(String value) {}",
                "}"));

        missingLegacyFlag.assertFailedWith("AW2006");
        inferredDefaultLegacyFlag.assertSucceeded();
        inferredDefaultLegacyFlag.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void acceptsConstructorInjectionWithoutASetter() {
        CompilationResult result = compile(source("example.ImmutablePerson",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public final class ImmutablePerson {",
                "  private final String name;",
                "  @ThriftConstructor",
                "  public ImmutablePerson(@ThriftField(1) String name) { this.name = name; }",
                "  @ThriftField(1)",
                "  public String getName() { return name; }",
                "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void validatesBothLvtAndGeneralParanamerSourceNames() {
        CompilationResult unsafeWithoutLvt = compile(source("example.PotentialArgConflict",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class PotentialArgConflict {",
                "  @ThriftField(2) public String arg0;",
                "  @ThriftField(1) public String value;",
                "  @ThriftConstructor",
                "  public PotentialArgConflict(@ThriftField(1) String value) {}",
                "}"));
        CompilationResult stableExplicitName = compile(source("example.StableParameterName",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class StableParameterName {",
                "  @ThriftField(2) public String arg0;",
                "  @ThriftField(1) public String value;",
                "  @ThriftConstructor",
                "  public StableParameterName(",
                "      @ThriftField(value=1, name=\"value\") String arbitrary) {}",
                "}"));

        unsafeWithoutLvt.assertFailedWith("AW2003");
        stableExplicitName.assertSucceeded();
        stableExplicitName.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void followsAnnotationParanamerAllOrNothingAndAnnotationOrderRules() {
        CompilationResult partialNames = compile(source("example.PartialAnnotationNames",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class PartialAnnotationNames {",
                "  @ThriftField(3) public String sourceLeft;",
                "  @ThriftField(1) public String left;",
                "  @ThriftField(2) public String sourceRight;",
                "  @ThriftField public void inject(",
                "      @ThriftField(value=1, name=\"left\") String sourceLeft,",
                "      @ThriftField(2) String sourceRight) {}",
                "}"));
        CompilationResult namedFirst = compile(source("example.NamedFirstConflict",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import javax.inject.Named;",
                "@ThriftStruct",
                "public class NamedFirstConflict {",
                "  @ThriftField(2) public String arg0;",
                "  @ThriftField(1) public String value;",
                "  @ThriftConstructor",
                "  public NamedFirstConflict(",
                "      @Named(\"arg0\") @ThriftField(1) String source) {}",
                "}"));
        CompilationResult thriftFieldFirst = compile(source("example.ThriftFieldFirstName",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import javax.inject.Named;",
                "@ThriftStruct",
                "public class ThriftFieldFirstName {",
                "  @ThriftField(2) public String arg0;",
                "  @ThriftField(1) public String value;",
                "  @ThriftConstructor",
                "  public ThriftFieldFirstName(",
                "      @ThriftField(value=1, name=\"value\")",
                "      @Named(\"arg0\") String source) {}",
                "}"));
        CompilationResult namedInference = compile(source("example.NamedInference",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import javax.inject.Named;",
                "@ThriftStruct",
                "public class NamedInference {",
                "  @ThriftConstructor",
                "  public NamedInference(@Named(\"value\") @ThriftField String source) {}",
                "  @ThriftField(1) public String getValue() { return \"\"; }",
                "}"));
        CompilationResult bareNamed = compile(source("example.BareNamed",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import javax.inject.Named;",
                "@ThriftStruct",
                "public class BareNamed {",
                "  @ThriftField(2) public String arg0;",
                "  @ThriftField(1) public String value;",
                "  @ThriftConstructor",
                "  public BareNamed(@Named @ThriftField(1) String source) {}",
                "}"));
        CompilationResult emptyNamedConflict = compile(source("example.EmptyNamedConflict",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import javax.inject.Named;",
                "@ThriftStruct",
                "public class EmptyNamedConflict {",
                "  @ThriftField(1) public String left;",
                "  @ThriftField(2) public String right;",
                "  @ThriftConstructor",
                "  public EmptyNamedConflict(",
                "      @Named(\"\") @ThriftField(1) String first,",
                "      @Named(\"\") @ThriftField(2) String second) {}",
                "}"));

        partialNames.assertFailedWith("AW2003");
        namedFirst.assertFailedWith("AW2003");
        thriftFieldFirst.assertSucceeded();
        thriftFieldFirst.assertNoThriftAnnotationLintDiagnostics();
        namedInference.assertSucceeded();
        namedInference.assertNoThriftAnnotationLintDiagnostics();
        bareNamed.assertSucceeded();
        bareNamed.assertNoThriftAnnotationLintDiagnostics();
        emptyNamedConflict.assertFailedWith("AW2003");
    }

    @Test
    void acceptsRecordModelsWhenTheCompilerSupportsRecords() {
        boolean recordsSupported = false;
        for (ElementKind kind : ElementKind.values()) {
            recordsSupported |= "RECORD".equals(kind.name());
        }
        assumeTrue(recordsSupported, "Records are unavailable on this JDK");

        CompilationResult result = compileWithLanguageLevel("16", source("example.RecordValue",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public record RecordValue(String value) {",
                "  @ThriftConstructor",
                "  public RecordValue(@ThriftField(1) String value) { this.value = value; }",
                "  @ThriftField(1)",
                "  public String value() { return value; }",
                "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void rejectsMultipleThriftConstructors() {
        CompilationResult result = compile(source("example.MultipleConstructors",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class MultipleConstructors {",
                "  @ThriftConstructor public MultipleConstructors() {}",
                "  @ThriftConstructor",
                "  public MultipleConstructors(@ThriftField(1) String value) {}",
                "  @ThriftField(1) public String value;",
                "}"));

        result.assertFailedWith("AW3002");
    }

    @Test
    void rejectsAStructWithoutAnAnnotatedOrPublicNoArgConstructor() {
        CompilationResult result = compile(source("example.NoUsableConstructor",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class NoUsableConstructor {",
                "  private String value;",
                "  public NoUsableConstructor(String value) { this.value = value; }",
                "  @ThriftField(1) public String getValue() { return value; }",
                "  @ThriftField(1) public void setValue(String value) { this.value = value; }",
                "}"));

        result.assertFailedWith("AW3003");
    }

    @Test
    void invalidZeroArgumentSetterProducesAUserDiagnosticInsteadOfAnInternalFailure() {
        CompilationResult result = compile(source("example.InvalidSetter",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class InvalidSetter {",
                "  @ThriftField(1)",
                "  public void setName() {}",
                "}"));

        result.assertFailedWith("AW3003");
        assertFalse(result.hasCode("AW9002"), result.diagnosticSummary());
    }

    @Test
    void rejectsAFieldWithoutAWritePath() {
        CompilationResult result = compile(source("example.ReadOnlyValue",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class ReadOnlyValue {",
                "  @ThriftField(1)",
                "  public String getValue() { return \"value\"; }",
                "}"));

        result.assertFailedWith("AW3001");
    }

    @Test
    void acceptsAValidBuilderAndRejectsOneWithoutABuildMethod() {
        CompilationResult valid = compile(source("example.BuilderValue",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct(builder=BuilderValue.Builder.class)",
                "public class BuilderValue {",
                "  private final String value;",
                "  private BuilderValue(String value) { this.value = value; }",
                "  @ThriftField(1) public String getValue() { return value; }",
                "  public static class Builder {",
                "    private String value;",
                "    @ThriftField(1) public void setValue(String value) { this.value = value; }",
                "    @ThriftConstructor public BuilderValue build() { return new BuilderValue(value); }",
                "  }",
                "}"));
        CompilationResult invalid = compile(source("example.InvalidBuilderValue",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct(builder=InvalidBuilderValue.Builder.class)",
                "public class InvalidBuilderValue {",
                "  @ThriftField(1) public String getValue() { return \"value\"; }",
                "  public static class Builder {",
                "    @ThriftField(1) public void setValue(String value) {}",
                "  }",
                "}"));

        valid.assertSucceeded();
        valid.assertNoThriftAnnotationLintDiagnostics();
        invalid.assertFailedWith("AW3005");
    }

    @Test
    void reportsHiddenInvalidBuilderFactoryMethods() {
        CompilationResult result = compile(source("example.HiddenBuilderFactory",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct(builder=HiddenBuilderFactory.Builder.class)",
                "public class HiddenBuilderFactory {",
                "  @ThriftField(1) public String getValue() { return \"\"; }",
                "  public static class BaseBuilder {",
                "    @ThriftConstructor private HiddenBuilderFactory build() {",
                "      return new HiddenBuilderFactory();",
                "    }",
                "  }",
                "  public static class Builder extends BaseBuilder {",
                "    @ThriftField(1) public void setValue(String value) {}",
                "    @ThriftConstructor public HiddenBuilderFactory build() {",
                "      return new HiddenBuilderFactory();",
                "    }",
                "  }",
                "}"));

        result.assertFailedWith("AW3004");
    }

    @Test
    void validatesMemberVisibility() {
        CompilationResult result = compile(source("example.PrivateAnnotatedField",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class PrivateAnnotatedField {",
                "  @ThriftField(1) private String value;",
                "}"));

        result.assertFailedWith("AW3004");
    }

    @Test
    void acceptsNestedSupportedContainerTypes() {
        CompilationResult result = compile(source("example.ContainerValue",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.List;",
                "import java.util.Map;",
                "import java.util.Set;",
                "@ThriftStruct",
                "public class ContainerValue {",
                "  @ThriftField(1)",
                "  public List<Map<String, Set<Nested>>> values;",
                "  @ThriftStruct",
                "  public static class Nested {",
                "    @ThriftField(1) public long id;",
                "  }",
                "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void acceptsOfficialSwiftPrimitiveCoercionsAndBinaryTypes() {
        CompilationResult result = compile(source("example.SupportedScalars",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.nio.ByteBuffer;",
                "@ThriftStruct",
                "public class SupportedScalars {",
                "  @ThriftField(1) public Boolean boolValue;",
                "  @ThriftField(2) public Byte byteValue;",
                "  @ThriftField(3) public Short shortValue;",
                "  @ThriftField(4) public Integer intValue;",
                "  @ThriftField(5) public Long longValue;",
                "  @ThriftField(6) public float primitiveFloat;",
                "  @ThriftField(7) public Float boxedFloat;",
                "  @ThriftField(8) public Double doubleValue;",
                "  @ThriftField(9) public ByteBuffer buffer;",
                "  @ThriftField(10) public byte[] bytes;",
                "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void ignoresTypeUseAnnotationsWhenComparingWireTypes() {
        CompilationResult result = compile(
                source("example.Marker",
                        "package example;",
                        "import java.lang.annotation.ElementType;",
                        "import java.lang.annotation.Target;",
                        "@Target(ElementType.TYPE_USE)",
                        "public @interface Marker {}"),
                source("example.TypeUseValue",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class TypeUseValue {",
                        "  @ThriftField(1) public @Marker int getValue() { return 0; }",
                        "  @ThriftField(1) public void setValue(int value) {}",
                        "}"),
                source("example.TypeUseEnum",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftEnum;",
                        "import com.facebook.swift.codec.ThriftEnumValue;",
                        "@ThriftEnum",
                        "public enum TypeUseEnum {",
                        "  VALUE;",
                        "  @ThriftEnumValue public @Marker Integer value() { return 1; }",
                        "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void rejectsUnsupportedAndConflictingTypes() {
        CompilationResult unsupported = compile(source("example.UnsupportedType",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class UnsupportedType {",
                "  @ThriftField(1) public Object value;",
                "}"));
        CompilationResult conflicting = compile(source("example.ConflictingTypes",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class ConflictingTypes {",
                "  @ThriftField(1) public String getValue() { return \"\"; }",
                "  @ThriftField(1) public void setValue(Integer value) {}",
                "}"));

        unsupported.assertFailedWith("AW4001");
        conflicting.assertFailedWith("AW4002");
    }

    @Test
    void rejectsCharAndRawContainers() {
        CompilationResult result = compile(source("example.UnsupportedShapes",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.List;",
                "@ThriftStruct",
                "public class UnsupportedShapes {",
                "  @ThriftField(1) public char character;",
                "  @ThriftField(2) public List rawValues;",
                "}"));

        result.assertFailedWith("AW4001");
    }

    @Test
    void recursiveFieldsMustBeOptional() {
        CompilationResult result = compile(source("example.RecursiveValue",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import static com.facebook.swift.codec.ThriftField.Recursiveness.TRUE;",
                "import static com.facebook.swift.codec.ThriftField.Requiredness.REQUIRED;",
                "@ThriftStruct",
                "public class RecursiveValue {",
                "  @ThriftField(value=1, isRecursive=TRUE, requiredness=REQUIRED)",
                "  public RecursiveValue next;",
                "}"));

        result.assertFailedWith("AW4003");
    }

    @Test
    void directStructRecursionMustBeDeclaredRecursive() {
        CompilationResult result = compile(source("example.DirectlyRecursive",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class DirectlyRecursive {",
                "  @ThriftField(1) public DirectlyRecursive next;",
                "}"));

        result.assertFailedWith("AW4003");
    }

    @Test
    void indirectStructRecursionMustContainADeclaredRecursiveEdge() {
        CompilationResult result = compile(
                source("example.RecursiveA",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class RecursiveA {",
                        "  @ThriftField(1) public RecursiveB b;",
                        "}"),
                source("example.RecursiveB",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class RecursiveB {",
                        "  @ThriftField(1) public RecursiveA a;",
                        "}"));

        result.assertFailedWith("AW4003");
    }

    @Test
    void detectsGenericCyclesAcrossDifferentTypeVariableNames() {
        CompilationResult result = compile(
                source("example.GenericA",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class GenericA<T> {",
                        "  @ThriftField(1) public GenericB<T> value;",
                        "}"),
                source("example.GenericB",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class GenericB<U> {",
                        "  @ThriftField(1) public GenericA<U> value;",
                        "}"));

        result.assertFailedWith("AW4003");
    }

    @Test
    void detectsExpandingGenericSelfCyclesWithoutUnboundedTraversal() {
        CompilationResult result = compile(source("example.ExpandingCycle",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.List;",
                "@ThriftStruct",
                "public class ExpandingCycle<T> {",
                "  @ThriftField(1) public ExpandingCycle<List<T>> next;",
                "}"));

        result.assertFailedWith("AW4003");
        assertFalse(result.hasCode("AW9002"), result.diagnosticSummary());
    }

    @Test
    void boundsBranchingGenericMetadataExpansion() {
        CompilationResult result = compile(source("example.BranchingExpansion",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.List;",
                "import java.util.Set;",
                "import static com.facebook.swift.codec.ThriftField.Recursiveness.TRUE;",
                "import static com.facebook.swift.codec.ThriftField.Requiredness.OPTIONAL;",
                "@ThriftStruct",
                "public class BranchingExpansion<T> {",
                "  @ThriftField(value=1, isRecursive=TRUE, requiredness=OPTIONAL)",
                "  public BranchingExpansion<List<T>> left;",
                "  @ThriftField(value=2, isRecursive=TRUE, requiredness=OPTIONAL)",
                "  public BranchingExpansion<Set<T>> right;",
                "}"));

        result.assertFailedWith("AW4003");
        assertFalse(result.hasCode("AW9002"), result.diagnosticSummary());
    }

    @Test
    void acceptsFiniteGenericMetadataClosures() {
        CompilationResult finiteChain = compile(
                source("example.FiniteA",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class FiniteA<T> {",
                        "  @ThriftField(1) public T value;",
                        "}"),
                source("example.FiniteB",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class FiniteB<T> {",
                        "  @ThriftField(1) public FiniteA<String> value;",
                        "}"),
                source("example.FiniteHolder",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class FiniteHolder {",
                        "  @ThriftField(1) public FiniteA<FiniteB<String>> value;",
                        "}"));
        CompilationResult convergingRecursion = compile(
                source("example.ConvergingNode",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "import static com.facebook.swift.codec.ThriftField.Recursiveness.TRUE;",
                        "import static com.facebook.swift.codec.ThriftField.Requiredness.OPTIONAL;",
                        "@ThriftStruct",
                        "public class ConvergingNode<T> {",
                        "  @ThriftField(1) public T value;",
                        "  @ThriftField(value=2, isRecursive=TRUE, requiredness=OPTIONAL)",
                        "  public ConvergingNode<Integer> next;",
                        "}"),
                source("example.ConvergingHolder",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class ConvergingHolder {",
                        "  @ThriftField(1) public ConvergingNode<String> value;",
                        "}"));

        finiteChain.assertSucceeded();
        finiteChain.assertNoThriftAnnotationLintDiagnostics();
        convergingRecursion.assertSucceeded();
        convergingRecursion.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void reportsOneStableDiagnosticForAMultiEdgeStronglyConnectedComponent() {
        CompilationResult result = compile(
                source("example.MultiCycleA",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class MultiCycleA {",
                        "  @ThriftField(1) public MultiCycleB primary;",
                        "  @ThriftField(2) public MultiCycleB secondary;",
                        "}"),
                source("example.MultiCycleB",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class MultiCycleB {",
                        "  @ThriftField(1) public MultiCycleC toC;",
                        "  @ThriftField(2) public MultiCycleC alternateC;",
                        "}"),
                source("example.MultiCycleC",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class MultiCycleC {",
                        "  @ThriftField(1) public MultiCycleA toA;",
                        "}"));

        result.assertFailedWith("AW4003");
        int cycleDiagnostics = 0;
        for (Diagnostic<? extends JavaFileObject> diagnostic : result.thriftAnnotationLintDiagnostics()) {
            if (diagnostic.getMessage(java.util.Locale.ENGLISH).startsWith("[AW4003]")) {
                cycleDiagnostics++;
            }
        }
        assertEquals(1, cycleDiagnostics, result.diagnosticSummary());
        assertTrue(
                result.diagnostic("AW4003").getMessage(java.util.Locale.ENGLISH).contains(
                        "example.MultiCycleC.toA -> example.MultiCycleA.primary -> "
                                + "example.MultiCycleB.alternateC -> example.MultiCycleC"),
                result.diagnosticSummary());
    }

    @Test
    void detectsCyclesAcrossAnnotationProcessingRounds() {
        CompilationResult result = compileWithAdditionalProcessor(
                new RoundBGenerator(),
                source("example.RoundA",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class RoundA {",
                        "  @ThriftField(1) public RoundB value;",
                        "}"));

        result.assertFailedWith("AW4003");
    }

    @Test
    void revalidatesFieldTypesAfterGeneratedSymbolsBecomeAvailable() {
        CompilationResult direct = compileWithAdditionalProcessor(
                new GeneratedValueGenerator(),
                source("example.GeneratedTypeConflict",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class GeneratedTypeConflict {",
                        "  @ThriftField(1) public GeneratedValue getValue() { return null; }",
                        "  @ThriftField(1) public void setValue(String value) {}",
                        "}"));
        CompilationResult genericUseSite = compileWithAdditionalProcessor(
                new GeneratedValueGenerator(),
                source("example.GeneratedGenericHolder",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class GeneratedGenericHolder {",
                        "  @ThriftField(1) public GeneratedBox<String> value;",
                        "}"),
                source("example.GeneratedBox",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class GeneratedBox<T> {",
                        "  @ThriftField(1) public GeneratedValue getValue() { return null; }",
                        "  @ThriftField(1) public void setValue(T value) {}",
                        "}"));

        direct.assertFailedWith("AW4002");
        genericUseSite.assertFailedWith("AW4002");
    }

    @Test
    void oneResolvedExactInstanceCannotClearAnUnresolvedSibling() {
        CompilationResult result = compileWithAdditionalProcessor(
                new DelayedGeneratedValueGenerator(),
                source("example.AUnresolvedContainerRoot",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "import java.util.ArrayList;",
                        "@ThriftStruct",
                        "public class AUnresolvedContainerRoot",
                        "    extends ArrayList<MixedRoundBox<LateGenerated>> {}"),
                source("example.ZResolvedContainerRoot",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "import java.util.ArrayList;",
                        "@ThriftStruct",
                        "public class ZResolvedContainerRoot",
                        "    extends ArrayList<MixedRoundBox<String>> {}"),
                source("example.MixedRoundBox",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class MixedRoundBox<T> {",
                        "  @ThriftField(1) public T getValue() { return null; }",
                        "  @ThriftField(1) public void setValue(String value) {}",
                        "}"));

        result.assertFailedWith("AW4002");
    }

    @Test
    void defersBuilderHierarchyAndUnionIdRulesUntilSymbolsResolve() {
        CompilationResult builder = compileWithAdditionalProcessor(
                new SourceGenerator(
                        "example.GeneratedBuilder",
                        "package example;\n"
                                + "import com.facebook.swift.codec.ThriftConstructor;\n"
                                + "import com.facebook.swift.codec.ThriftField;\n"
                                + "public class GeneratedBuilder {\n"
                                + "  @ThriftField(1) public String value;\n"
                                + "  @ThriftConstructor public GeneratedBuilderModel build() {\n"
                                + "    return new GeneratedBuilderModel();\n"
                                + "  }\n"
                                + "}\n"),
                source("example.GeneratedBuilderModel",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct(builder=GeneratedBuilder.class)",
                        "public class GeneratedBuilderModel {",
                        "  @ThriftField(1) public String value;",
                        "}"));
        CompilationResult hierarchy = compileWithAdditionalProcessor(
                new SourceGenerator(
                        "example.GeneratedBase",
                        "package example;\n"
                                + "import com.facebook.swift.codec.ThriftField;\n"
                                + "public class GeneratedBase {\n"
                                + "  @ThriftField(1) public Object value;\n"
                                + "}\n"),
                source("example.GeneratedBaseModel",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class GeneratedBaseModel extends GeneratedBase {}"));
        CompilationResult unionId = compileWithAdditionalProcessor(
                new SourceGenerator(
                        "example.GeneratedId",
                        "package example;\npublic class GeneratedId {}\n"),
                source("example.GeneratedIdUnion",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftUnion;",
                        "import com.facebook.swift.codec.ThriftUnionId;",
                        "@ThriftUnion",
                        "public class GeneratedIdUnion {",
                        "  @ThriftUnionId public GeneratedId id;",
                        "  @ThriftField(1) public String value;",
                        "}"));

        builder.assertSucceeded();
        builder.assertNoThriftAnnotationLintDiagnostics();
        hierarchy.assertFailedWith("AW4001");
        unionId.assertFailedWith("AW5001");
    }

    @Test
    void explicitlyOptionalRecursiveEdgeBreaksADirectCycle() {
        CompilationResult result = compile(source("example.AllowedRecursion",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import static com.facebook.swift.codec.ThriftField.Recursiveness.TRUE;",
                "import static com.facebook.swift.codec.ThriftField.Requiredness.OPTIONAL;",
                "@ThriftStruct",
                "public class AllowedRecursion {",
                "  @ThriftField(value=1, isRecursive=TRUE, requiredness=OPTIONAL)",
                "  public AllowedRecursion next;",
                "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void nonStaticInnerStructHasNoRuntimeNoArgConstructionPath() {
        CompilationResult result = compile(source("example.ModelContainer",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "public class ModelContainer {",
                "  @ThriftStruct",
                "  public class InnerModel {",
                "    @ThriftField(1) public String value;",
                "  }",
                "}"));

        result.assertFailedWith("AW3003");
    }

    @Test
    void nonStaticBuilderHasNoRuntimeNoArgConstructionPath() {
        CompilationResult result = compile(source("example.NonStaticBuilderModel",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct(builder=NonStaticBuilderModel.Builder.class)",
                "public class NonStaticBuilderModel {",
                "  private String value;",
                "  @ThriftField(1) public String getValue() { return value; }",
                "  public class Builder {",
                "    @ThriftField(1)",
                "    public void setValue(String value) { NonStaticBuilderModel.this.value = value; }",
                "    @ThriftConstructor",
                "    public NonStaticBuilderModel build() { return NonStaticBuilderModel.this; }",
                "  }",
                "}"));

        result.assertFailedWith("AW3005");
    }

    @Test
    void rejectsAbstractConstructionTypesButAllowsAnAbstractModelWithAConcreteBuilder() {
        CompilationResult abstractModel = compile(source("example.AbstractModelWithoutBuilder",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public abstract class AbstractModelWithoutBuilder {",
                "  @ThriftField(1) public String value;",
                "}"));
        CompilationResult abstractBuilder = compile(source("example.ModelWithAbstractBuilder",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct(builder=ModelWithAbstractBuilder.Builder.class)",
                "public class ModelWithAbstractBuilder {",
                "  @ThriftField(1) public String getValue() { return \"\"; }",
                "  public abstract static class Builder {",
                "    @ThriftField(1) public abstract void setValue(String value);",
                "    @ThriftConstructor public abstract ModelWithAbstractBuilder build();",
                "  }",
                "}"));
        CompilationResult concreteBuilder = compile(source("example.AbstractModelWithBuilder",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct(builder=AbstractModelWithBuilder.Builder.class)",
                "public abstract class AbstractModelWithBuilder {",
                "  @ThriftField(1) public abstract String getValue();",
                "  public static class Builder {",
                "    private String value;",
                "    @ThriftField(1) public void setValue(String value) { this.value = value; }",
                "    @ThriftConstructor public AbstractModelWithBuilder build() {",
                "      return new Value(value);",
                "    }",
                "  }",
                "  private static final class Value extends AbstractModelWithBuilder {",
                "    private final String value;",
                "    private Value(String value) { this.value = value; }",
                "    public String getValue() { return value; }",
                "  }",
                "}"));

        abstractModel.assertFailedWith("AW3003");
        abstractBuilder.assertFailedWith("AW3005");
        concreteBuilder.assertSucceeded();
        concreteBuilder.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void rejectsFinalInjectionFieldsButAllowsFinalReadFieldsWithABuilder() {
        CompilationResult directInjection = compile(source("example.FinalInjectionField",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class FinalInjectionField {",
                "  @ThriftField(1) public final String value = \"\";",
                "}"));
        CompilationResult builderReadField = compile(source("example.FinalBuilderField",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct(builder=FinalBuilderField.Builder.class)",
                "public class FinalBuilderField {",
                "  @ThriftField(1) public final String value;",
                "  private FinalBuilderField(String value) { this.value = value; }",
                "  public static class Builder {",
                "    private String value;",
                "    @ThriftField(1) public void setValue(String value) { this.value = value; }",
                "    @ThriftConstructor public FinalBuilderField build() {",
                "      return new FinalBuilderField(value);",
                "    }",
                "  }",
                "}"));

        directInjection.assertFailedWith("AW3004");
        builderReadField.assertSucceeded();
        builderReadField.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void swiftTwoPhaseIdInferenceDoesNotTransitivelyBackPropagateIds() {
        CompilationResult result = compile(source("example.NoFixedPointInference",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class NoFixedPointInference {",
                "  @ThriftField(name=\"b\") public String a;",
                "  @ThriftField public String b;",
                "  @ThriftField(value=1, name=\"c\")",
                "  public String getB() { return b; }",
                "}"));

        result.assertFailedWith("AW2001");
    }

    @Test
    void swiftSecondInferencePhaseDetectsIdsIntroducedByTheFirstPhase() {
        CompilationResult result = compile(source("example.StagedIdConflict",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class StagedIdConflict {",
                "  @ThriftField(value=1, name=\"b\") public String a;",
                "  @ThriftField public String b;",
                "  @ThriftField(value=2, name=\"c\")",
                "  public String getB() { return b; }",
                "}"));

        result.assertFailedWith("AW2003");
    }

    @Test
    void supportsGenericStructAndBuilderWithMatchingBoundedTypeParameters() {
        CompilationResult result = compile(source("example.GenericModel",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct(builder=GenericModel.Builder.class)",
                "public class GenericModel<T extends GenericModel.Payload> {",
                "  private final T value;",
                "  private GenericModel(T value) { this.value = value; }",
                "  @ThriftField(1) public T getValue() { return value; }",
                "  public static class Builder<T extends Payload> {",
                "    private T value;",
                "    @ThriftField(1) public void setValue(T value) { this.value = value; }",
                "    @ThriftConstructor public GenericModel<T> build() { return new GenericModel<T>(value); }",
                "  }",
                "  @ThriftStruct",
                "  public static class Payload {",
                "    @ThriftField(1) public String text;",
                "  }",
                "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void defersTypeVariablesWithoutHidingConcreteTypeConflicts() {
        CompilationResult compatibleScalar = compile(source("example.GenericScalar",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class GenericScalar<T> {",
                "  private T value;",
                "  @ThriftField(1) public T getValue() { return value; }",
                "  @ThriftField(1) public void setValue(String value) {}",
                "}"));
        CompilationResult compatibleContainer = compile(source("example.GenericContainer",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.List;",
                "@ThriftStruct",
                "public class GenericContainer<T> {",
                "  @ThriftField(1) public List<T> getValues() { return null; }",
                "  @ThriftField(1) public void setValues(List<String> values) {}",
                "}"));
        CompilationResult incompatibleConcreteTypes = compile(source("example.GenericConflict",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class GenericConflict<T> {",
                "  @ThriftField(1) public Integer value;",
                "  @ThriftField(1) public T getValue() { return null; }",
                "  @ThriftField(1) public void setValue(String value) {}",
                "}"));

        compatibleScalar.assertSucceeded();
        compatibleScalar.assertNoThriftAnnotationLintDiagnostics();
        compatibleContainer.assertSucceeded();
        compatibleContainer.assertNoThriftAnnotationLintDiagnostics();
        incompatibleConcreteTypes.assertFailedWith("AW4002");
    }

    @Test
    void preservesGenericStructArgumentsInsideContainers() {
        CompilationResult result = compile(source("example.NestedGenericConflict",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.List;",
                "@ThriftStruct",
                "public class NestedGenericConflict {",
                "  @ThriftField(1) public List<Box<String>> getValues() { return null; }",
                "  @ThriftField(1) public void setValues(List<Box<Integer>> values) {}",
                "  @ThriftStruct",
                "  public static class Box<T> {",
                "    @ThriftField(1) public T value;",
                "  }",
                "}"));

        result.assertFailedWith("AW4002");
    }

    @Test
    void preservesExactJavaTypesForStructArgumentsInsideContainers() {
        CompilationResult result = compile(source("example.ExactNestedGenericConflict",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.ArrayList;",
                "import java.util.List;",
                "@ThriftStruct",
                "public class ExactNestedGenericConflict {",
                "  @ThriftField(1)",
                "  public List<Box<ArrayList<String>>> getValues() { return null; }",
                "  @ThriftField(1)",
                "  public void setValues(List<Box<List<String>>> values) {}",
                "  @ThriftStruct",
                "  public static class Box<T> {",
                "    @ThriftField(1) public T value;",
                "  }",
                "}"));

        result.assertFailedWith("AW4002");
    }

    @Test
    void defersTypeVariablesInsideExactStructArgumentIdentities() {
        CompilationResult result = compile(source("example.NestedDeferredArgument",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.List;",
                "@ThriftStruct",
                "public class NestedDeferredArgument<T> {",
                "  @ThriftField(1) public List<Box<List<T>>> getValues() { return null; }",
                "  @ThriftField(1) public void setValues(List<Box<List<String>>> values) {}",
                "  @ThriftStruct",
                "  public static class Box<T> {",
                "    @ThriftField(1) public T value;",
                "  }",
                "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void preservesWildcardIdentityForRecursiveStructReferences() {
        CompilationResult result = compile(source("example.WildcardStructConflict",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.List;",
                "@ThriftStruct",
                "public class WildcardStructConflict {",
                "  @ThriftField(1) public List<? extends Value> getValues() { return null; }",
                "  @ThriftField(1) public void setValues(List<Value> values) {}",
                "  @ThriftStruct",
                "  public static class Value {",
                "    @ThriftField(1) public String value;",
                "  }",
                "}"));

        result.assertFailedWith("AW4002");
    }

    @Test
    void preservesExactTypesForExplicitlyRecursiveDirectFields() {
        CompilationResult result = compile(source("example.RecursiveGenericConflict",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import static com.facebook.swift.codec.ThriftField.Requiredness.OPTIONAL;",
                "import static com.facebook.swift.codec.ThriftField.Recursiveness.TRUE;",
                "@ThriftStruct",
                "public class RecursiveGenericConflict {",
                "  @ThriftField(value=1, requiredness=OPTIONAL, isRecursive=TRUE)",
                "  public Box<String> getValue() { return null; }",
                "  @ThriftField(value=1, requiredness=OPTIONAL, isRecursive=TRUE)",
                "  public void setValue(Box<Integer> value) {}",
                "  @ThriftStruct public static class Box<T> {",
                "    @ThriftField(1) public T value;",
                "  }",
                "}"));

        result.assertFailedWith("AW4002");
    }

    @Test
    void preservesConcreteContainerImplementationsForRecursiveDirectFields() {
        CompilationResult result = compile(source("example.RecursiveContainerConflict",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.ArrayList;",
                "import java.util.List;",
                "import static com.facebook.swift.codec.ThriftField.Requiredness.OPTIONAL;",
                "import static com.facebook.swift.codec.ThriftField.Recursiveness.TRUE;",
                "@ThriftStruct",
                "public class RecursiveContainerConflict {",
                "  @ThriftField(value=1, requiredness=OPTIONAL, isRecursive=TRUE)",
                "  public ArrayList<String> getValues() { return null; }",
                "  @ThriftField(value=1, requiredness=OPTIONAL, isRecursive=TRUE)",
                "  public void setValues(List<String> values) {}",
                "}"));

        result.assertFailedWith("AW4002");
    }

    @Test
    void includesParameterizedOwnersInExactJavaTypeIdentity() {
        CompilationResult conflict = compile(source("example.OwnerIdentityConflict",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.List;",
                "@ThriftStruct",
                "public class OwnerIdentityConflict {",
                "  @ThriftField(1)",
                "  public List<Box<Outer<String>.Inner>> getValues() { return null; }",
                "  @ThriftField(1)",
                "  public void setValues(List<Box<Outer<Long>.Inner>> values) {}",
                "  public static class Outer<T> { public class Inner {} }",
                "  @ThriftStruct public static class Box<T> {",
                "    @ThriftField(1) public String marker;",
                "  }",
                "}"));
        CompilationResult deferred = compile(source("example.OwnerIdentityDeferred",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.List;",
                "@ThriftStruct",
                "public class OwnerIdentityDeferred<T> {",
                "  @ThriftField(1)",
                "  public List<Box<Outer<T>.Inner>> getValues() { return null; }",
                "  @ThriftField(1)",
                "  public void setValues(List<Box<Outer<String>.Inner>> values) {}",
                "  public static class Outer<T> { public class Inner {} }",
                "  @ThriftStruct public static class Box<T> {",
                "    @ThriftField(1) public String marker;",
                "  }",
                "}"));

        conflict.assertFailedWith("AW4002");
        deferred.assertSucceeded();
        deferred.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void defersTypeVariablesInsideExactArrayAndWildcardWrappers() {
        CompilationResult result = compile(source("example.DeferredExactWrappers",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.List;",
                "@ThriftStruct",
                "public class DeferredExactWrappers<T> {",
                "  @ThriftField(1) public List<Box<T[]>> getArrays() { return null; }",
                "  @ThriftField(1) public void setArrays(List<Box<String[]>> values) {}",
                "  @ThriftField(2) public List<Box<? extends T>> getWildcards() { return null; }",
                "  @ThriftField(2)",
                "  public void setWildcards(List<Box<? extends String>> values) {}",
                "  @ThriftStruct public static class Box<T> {",
                "    @ThriftField(1) public String marker;",
                "  }",
                "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void rejectsUnboundedExecutableTypeVariables() {
        CompilationResult result = compile(source("example.MethodTypeVariable",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class MethodTypeVariable {",
                "  @ThriftField(1) public <T> T getValue() { return null; }",
                "  @ThriftField(1) public <T> void setValue(T value) {}",
                "}"));

        result.assertFailedWith("AW4001");
    }

    @Test
    void keepsBoundedExecutableTypeVariablesAsExactJavaTypes() {
        CompilationResult compatible = compile(source("example.BoundedMethodTypeCompatibility",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class BoundedMethodTypeCompatibility {",
                "  @ThriftField(1) public <T extends Value> T getValue() { return null; }",
                "  @ThriftField(1) public void setValue(Value value) {}",
                "  @ThriftStruct public static class Value {",
                "    @ThriftField(1) public String value;",
                "  }",
                "}"));

        compatible.assertSucceeded();
        compatible.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void detectsCyclesThroughBoundedExecutableTypeVariables() {
        CompilationResult result = compile(source("example.BoundedVariableCycle",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class BoundedVariableCycle {",
                "  @ThriftField(1)",
                "  public <T extends BoundedVariableCycle> T getNext() { return null; }",
                "  @ThriftField(1)",
                "  public <T extends BoundedVariableCycle> void setNext(T value) {}",
                "}"));

        result.assertFailedWith("AW4003");
    }

    @Test
    void preservesIntersectionBoundTypeVariablesInsideContainers() {
        CompilationResult result = compile(source("example.IntersectionContainerConflict",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.io.Serializable;",
                "import java.util.List;",
                "@ThriftStruct",
                "public class IntersectionContainerConflict {",
                "  @ThriftField(1)",
                "  public <T extends Value & Serializable> List<T> getValues() { return null; }",
                "  @ThriftField(1)",
                "  public void setValues(List<Value> values) {}",
                "  @ThriftStruct public static class Value implements Serializable {",
                "    private static final long serialVersionUID = 1L;",
                "    @ThriftField(1) public String value;",
                "  }",
                "}"));

        result.assertFailedWith("AW4002");
    }

    @Test
    void rejectsGenericBuildersRequestedThroughExecutableTypeVariables() {
        CompilationResult result = compile(source("example.VariableBuilderRequest",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class VariableBuilderRequest {",
                "  @ThriftField(1)",
                "  public <T extends Built<String>> T getValue() { return null; }",
                "  @ThriftStruct(builder=Built.Builder.class)",
                "  public static class Built<X> {",
                "    @ThriftField(1) public X getValue() { return null; }",
                "    public static class Builder<X> {",
                "      @ThriftField(1) public void setValue(X value) {}",
                "      @ThriftConstructor public Built<X> build() { return new Built<X>(); }",
                "    }",
                "  }",
                "}"));

        result.assertFailedWith("AW3005");
    }

    @Test
    void rejectsGenericBuildersRequestedThroughWildcards() {
        CompilationResult result = compile(source("example.WildcardBuilderRequest",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.List;",
                "@ThriftStruct",
                "public class WildcardBuilderRequest {",
                "  @ThriftField(1) public List<? extends Built<String>> values;",
                "  @ThriftStruct(builder=Built.Builder.class)",
                "  public static class Built<X> {",
                "    @ThriftField(1) public X getValue() { return null; }",
                "    public static class Builder<X> {",
                "      @ThriftField(1) public void setValue(X value) {}",
                "      @ThriftConstructor public Built<X> build() { return new Built<X>(); }",
                "    }",
                "  }",
                "}"));

        result.assertFailedWith("AW3005");
    }

    @Test
    void rejectsAnnotatedMembersDeclaredByNonPublicBaseTypes() {
        CompilationResult result = compile(source("example.PublicModel",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "class HiddenBase {",
                "  @ThriftField(1) public String value;",
                "}",
                "@ThriftStruct",
                "public class PublicModel extends HiddenBase {}"));

        result.assertFailedWith("AW3004");
    }

    @Test
    void doesNotRejectUnusedOrRawGenericStructArguments() {
        CompilationResult result = compile(source("example.PhantomArguments",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class PhantomArguments {",
                "  @ThriftField(1) public Phantom<Object> parameterized;",
                "  @ThriftField(2) public Phantom raw;",
                "  @ThriftStruct",
                "  public static class Phantom<T> {",
                "    @ThriftField(1) public String value;",
                "  }",
                "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void recursiveIterableTypesProduceAStableUnsupportedTypeDiagnostic() {
        CompilationResult result = compile(source("example.RecursiveContainerHolder",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.ArrayList;",
                "@ThriftStruct",
                "public class RecursiveContainerHolder {",
                "  @ThriftField(1) public RecursiveValues values;",
                "  public static class RecursiveValues extends ArrayList<RecursiveValues> {}",
                "}"));

        result.assertFailedWith("AW4001");
        assertFalse(result.hasCode("AW9002"), result.diagnosticSummary());
    }

    @Test
    void classifiesAnnotatedContainerSubtypesAsContainersBeforeStructs() {
        CompilationResult result = compile(source("example.AnnotatedContainerModel",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.ArrayList;",
                "@ThriftStruct",
                "public class AnnotatedContainerModel {",
                "  @ThriftField(1) public NodeList getChildren() { return null; }",
                "  @ThriftField(1)",
                "  public void setChildren(java.util.List<AnnotatedContainerModel> children) {}",
                "  @ThriftStruct",
                "  public static class NodeList extends ArrayList<AnnotatedContainerModel> {}",
                "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void ignoresStructMetadataOnAnAnnotatedContainerRootLikeSwift() {
        CompilationResult result = compile(source("example.AnnotatedContainerRoot",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.ArrayList;",
                "@ThriftStruct",
                "public class AnnotatedContainerRoot extends ArrayList<String> {",
                "  @ThriftField public Object ignoredStructField;",
                "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void validatesTheResolvedShapeOfAnnotatedContainerRoots() {
        CompilationResult unsupportedElement = compile(source("example.UnsupportedContainerRoot",
                "package example;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.ArrayList;",
                "@ThriftStruct",
                "public class UnsupportedContainerRoot extends ArrayList<Object> {}"));
        CompilationResult rawContainer = compile(source("example.RawContainerRoot",
                "package example;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.ArrayList;",
                "@ThriftStruct",
                "public class RawContainerRoot extends ArrayList {}"));
        CompilationResult iterable = compile(source("example.IterableContainerRoot",
                "package example;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.Collections;",
                "import java.util.Iterator;",
                "@ThriftStruct",
                "public class IterableContainerRoot implements Iterable<String> {",
                "  public Iterator<String> iterator() {",
                "    return Collections.<String>emptyList().iterator();",
                "  }",
                "}"));

        unsupportedElement.assertFailedWith("AW4001");
        rawContainer.assertFailedWith("AW4001");
        iterable.assertSucceeded();
        iterable.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void reclassifiesAnAnnotatedContainerAfterItsGeneratedSupertypeResolves() {
        CompilationResult result = compileWithAdditionalProcessor(
                new SourceGenerator(
                        "example.GeneratedStringList",
                        "package example;\n"
                                + "public class GeneratedStringList "
                                + "extends java.util.ArrayList<String> {}\n"),
                source("example.GeneratedContainerRoot",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class GeneratedContainerRoot extends GeneratedStringList {",
                        "  @ThriftField public Object ignoredStructField;",
                        "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void upgradesAVisibleIterableToAGeneratedHigherPriorityMap() {
        CompilationResult result = compileWithAdditionalProcessor(
                new SourceGenerator(
                        "example.GeneratedMapBase",
                        "package example;\n"
                                + "public class GeneratedMapBase<K> "
                                + "extends java.util.HashMap<K, String> {}\n"),
                source("example.GeneratedMapContainerRoot",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "import java.util.Iterator;",
                        "@ThriftStruct",
                        "public abstract class GeneratedMapContainerRoot",
                        "    extends GeneratedMapBase<Object> implements Iterable<String> {}"));

        result.assertFailedWith("AW4001");
    }

    @Test
    void defersAVisibleInvalidIterableUntilItsGeneratedMapShapeIsStable() {
        CompilationResult result = compileWithAdditionalProcessor(
                new SourceGenerator(
                        "example.GeneratedGoodMapBase",
                        "package example;\n"
                                + "public class GeneratedGoodMapBase "
                                + "extends java.util.HashMap<String, String> {}\n"),
                source("example.GeneratedGoodMapContainerRoot",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public abstract class GeneratedGoodMapContainerRoot",
                        "    extends GeneratedGoodMapBase implements Iterable<Object> {}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void revalidatesHistoricalFieldContainersAfterAGeneratedPriorityUpgrade() {
        CompilationResult result = compileWithAdditionalProcessor(
                new SourceGenerator(
                        "example.GeneratedSetBase",
                        "package example;\n"
                                + "public class GeneratedSetBase "
                                + "extends java.util.HashSet<String> {}\n"),
                source("example.HybridListSet",
                        "package example;",
                        "import java.util.List;",
                        "public abstract class HybridListSet",
                        "    extends GeneratedSetBase implements List<String> {}"),
                source("example.GeneratedContainerFieldHolder",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "import java.util.List;",
                        "@ThriftStruct",
                        "public class GeneratedContainerFieldHolder {",
                        "  @ThriftField(1)",
                        "  public HybridListSet getValues() { return null; }",
                        "  @ThriftField(1)",
                        "  public void setValues(List<String> values) {}",
                        "}"));

        result.assertFailedWith("AW4002");
    }

    @Test
    void buffersHistoricalFieldFindingsUntilGeneratedContainerPriorityIsStable() {
        CompilationResult result = compileWithAdditionalProcessor(
                new SourceGenerator(
                        "example.GeneratedGoodFieldMapBase",
                        "package example;\n"
                                + "public class GeneratedGoodFieldMapBase "
                                + "extends java.util.HashMap<String, String> {}\n"),
                source("example.GoodHybridMap",
                        "package example;",
                        "public abstract class GoodHybridMap",
                        "    extends GeneratedGoodFieldMapBase implements Iterable<String> {}"),
                source("example.GeneratedGoodContainerFieldHolder",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "import java.util.Map;",
                        "@ThriftStruct",
                        "public class GeneratedGoodContainerFieldHolder {",
                        "  @ThriftField(1)",
                        "  public GoodHybridMap getValues() { return null; }",
                        "  @ThriftField(1)",
                        "  public void setValues(Map<String, String> values) {}",
                        "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void rejectsConcreteContainerTypesOnDecodedInjectionPaths() {
        CompilationResult list = compile(source("example.ConcreteListWriter",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.LinkedList;",
                "import java.util.List;",
                "@ThriftStruct",
                "public class ConcreteListWriter {",
                "  @ThriftField(1) public List<String> getValues() { return null; }",
                "  @ThriftField(1) public void setValues(LinkedList<String> values) {}",
                "}"));
        CompilationResult set = compile(source("example.ConcreteSetWriter",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.HashSet;",
                "import java.util.Set;",
                "@ThriftStruct",
                "public class ConcreteSetWriter {",
                "  @ThriftField(1) public Set<String> getValues() { return null; }",
                "  @ThriftField(1) public void setValues(HashSet<String> values) {}",
                "}"));
        CompilationResult map = compile(source("example.ConcreteMapWriter",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.HashMap;",
                "import java.util.Map;",
                "@ThriftStruct",
                "public class ConcreteMapWriter {",
                "  @ThriftField(1) public Map<String, String> getValues() { return null; }",
                "  @ThriftField(1)",
                "  public void setValues(HashMap<String, String> values) {}",
                "}"));
        CompilationResult iterableReader = compile(source("example.IterableReader",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.List;",
                "@ThriftStruct",
                "public class IterableReader {",
                "  @ThriftField(1) public Iterable<String> getValues() { return null; }",
                "  @ThriftField(1) public void setValues(List<String> values) {}",
                "}"));
        CompilationResult nested = compile(source("example.NestedConcreteListWriter",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.LinkedList;",
                "import java.util.List;",
                "@ThriftStruct",
                "public class NestedConcreteListWriter {",
                "  @ThriftField(1) public List<LinkedList<String>> values;",
                "}"));

        list.assertFailedWith("AW4002");
        set.assertFailedWith("AW4002");
        map.assertFailedWith("AW4002");
        iterableReader.assertFailedWith("AW4002");
        nested.assertFailedWith("AW4002");
    }

    @Test
    void validatesUnionIdConstructorAndRequiredness() {
        CompilationResult valid = compile(source("example.ValidUnion",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftUnion;",
                "import com.facebook.swift.codec.ThriftUnionId;",
                "@ThriftUnion",
                "public class ValidUnion {",
                "  @ThriftUnionId public short id;",
                "  @ThriftField(1) public String text;",
                "}"));
        CompilationResult missingId = compile(source("example.MissingUnionId",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftUnion;",
                "@ThriftUnion",
                "public class MissingUnionId {",
                "  @ThriftField(1) public String text;",
                "}"));
        CompilationResult invalidConstructor = compile(source("example.InvalidUnionConstructor",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftUnion;",
                "import com.facebook.swift.codec.ThriftUnionId;",
                "@ThriftUnion",
                "public class InvalidUnionConstructor {",
                "  @ThriftUnionId public short id;",
                "  @ThriftField(1) public String text;",
                "  @ThriftField(2) public Integer number;",
                "  @ThriftConstructor",
                "  public InvalidUnionConstructor(@ThriftField(1) String text,",
                "      @ThriftField(2) Integer number) {}",
                "}"));
        CompilationResult invalidRequiredness = compile(source("example.RequiredUnionField",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftUnion;",
                "import com.facebook.swift.codec.ThriftUnionId;",
                "import static com.facebook.swift.codec.ThriftField.Requiredness.REQUIRED;",
                "@ThriftUnion",
                "public class RequiredUnionField {",
                "  @ThriftUnionId public short id;",
                "  @ThriftField(value=1, requiredness=REQUIRED) public String text;",
                "}"));

        valid.assertSucceeded();
        valid.assertNoThriftAnnotationLintDiagnostics();
        missingId.assertFailedWith("AW5001");
        invalidConstructor.assertFailedWith("AW5002");
        invalidRequiredness.assertFailedWith("AW5003");
    }

    @Test
    void rejectsBothUnionDiscriminatorNameInferenceCollisions() {
        CompilationResult reservedName = compile(source("example.ReservedUnionName",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftUnion;",
                "import com.facebook.swift.codec.ThriftUnionId;",
                "@ThriftUnion",
                "public class ReservedUnionName {",
                "  @ThriftUnionId public short id;",
                "  @ThriftField(value=1, name=\"_union_id\") public String value;",
                "}"));
        CompilationResult extractedName = compile(source("example.ExtractedUnionName",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftUnion;",
                "import com.facebook.swift.codec.ThriftUnionId;",
                "@ThriftUnion",
                "public class ExtractedUnionName {",
                "  @ThriftUnionId public short type;",
                "  @ThriftField(value=1, name=\"payload\")",
                "  public String getType() { return \"\"; }",
                "  @ThriftField(value=1, name=\"payload\")",
                "  public void setType(String value) {}",
                "}"));

        reservedName.assertFailedWith("AW2003");
        extractedName.assertFailedWith("AW2003");
    }

    @Test
    void validatesUnionDiscriminatorCollisionsWithoutLocalVariableTables() {
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
        assertTrue(result.diagnosticSummary().contains("omits LocalVariableTable"),
                result.diagnosticSummary());
    }

    @Test
    void rejectsFieldBasedUnionDiscriminatorsThatCannotBeInjectedIntoBuilders() {
        CompilationResult result = compile(source("example.UnsafeBuilderUnion",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftUnion;",
                "import com.facebook.swift.codec.ThriftUnionId;",
                "@ThriftUnion(builder=UnsafeBuilderUnion.Builder.class)",
                "public class UnsafeBuilderUnion {",
                "  @ThriftUnionId public short id;",
                "  private final String value;",
                "  private UnsafeBuilderUnion(String value) { this.value = value; }",
                "  @ThriftField(1) public String getValue() { return value; }",
                "  public static class Builder {",
                "    public Builder() {}",
                "    @ThriftConstructor",
                "    public UnsafeBuilderUnion build(@ThriftField(1) String value) {",
                "      return new UnsafeBuilderUnion(value);",
                "    }",
                "  }",
                "}"));

        result.assertFailedWith("AW5001");
    }

    @Test
    void requiresDeterministicConstructionForEveryUnionVariant() {
        CompilationResult missingVariant = compile(source("example.IncompleteUnionConstruction",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftUnion;",
                "import com.facebook.swift.codec.ThriftUnionId;",
                "@ThriftUnion",
                "public class IncompleteUnionConstruction {",
                "  @ThriftUnionId public short id;",
                "  public IncompleteUnionConstruction() {}",
                "  @ThriftConstructor",
                "  public IncompleteUnionConstruction(",
                "      @ThriftField(value=1, name=\"text\") String text) {}",
                "  @ThriftField(1) public String getText() { return null; }",
                "  @ThriftField(2) public Integer number;",
                "}"));
        CompilationResult duplicateVariant = compile(source("example.AmbiguousUnionConstruction",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftUnion;",
                "import com.facebook.swift.codec.ThriftUnionId;",
                "import java.util.List;",
                "@ThriftUnion",
                "public class AmbiguousUnionConstruction {",
                "  @ThriftUnionId public short id;",
                "  @ThriftConstructor",
                "  public AmbiguousUnionConstruction(",
                "      @ThriftField(value=1, name=\"values\") List<String> values) {}",
                "  @ThriftConstructor",
                "  public AmbiguousUnionConstruction(",
                "      @ThriftField(value=1, name=\"values\") Iterable<String> values) {}",
                "  @ThriftField(1) public List<String> getValues() { return null; }",
                "}"));

        missingVariant.assertFailedWith("AW5002");
        duplicateVariant.assertFailedWith("AW5002");
    }

    @Test
    void acceptsZeroArgumentOrPerVariantUnionConstruction() {
        CompilationResult zeroArgument = compile(source("example.ZeroArgumentUnion",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftUnion;",
                "import com.facebook.swift.codec.ThriftUnionId;",
                "@ThriftUnion",
                "public class ZeroArgumentUnion {",
                "  @ThriftUnionId public short id;",
                "  @ThriftConstructor public ZeroArgumentUnion() {}",
                "  @ThriftField(1) public String text;",
                "  @ThriftField(2) public Integer number;",
                "}"));
        CompilationResult perVariant = compile(source("example.PerVariantUnion",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftUnion;",
                "import com.facebook.swift.codec.ThriftUnionId;",
                "@ThriftUnion",
                "public class PerVariantUnion {",
                "  @ThriftUnionId public short id;",
                "  private String text;",
                "  private Integer number;",
                "  @ThriftConstructor",
                "  public PerVariantUnion(@ThriftField(value=1, name=\"text\") String text) {",
                "    this.text = text;",
                "  }",
                "  @ThriftConstructor",
                "  public PerVariantUnion(@ThriftField(value=2, name=\"number\") Integer number) {",
                "    this.number = number;",
                "  }",
                "  @ThriftField(1) public String getText() { return text; }",
                "  @ThriftField(2) public Integer getNumber() { return number; }",
                "}"));

        zeroArgument.assertSucceeded();
        zeroArgument.assertNoThriftAnnotationLintDiagnostics();
        perVariant.assertSucceeded();
        perVariant.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void unionIdMembersMustUsePrimitiveShortAndRemainWritable() {
        CompilationResult boxedShort = compile(source("example.BoxedUnionId",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftUnion;",
                "import com.facebook.swift.codec.ThriftUnionId;",
                "@ThriftUnion",
                "public class BoxedUnionId {",
                "  @ThriftUnionId public Short id;",
                "  @ThriftField(1) public String value;",
                "}"));
        CompilationResult wrongType = compile(source("example.IntegerUnionId",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftUnion;",
                "import com.facebook.swift.codec.ThriftUnionId;",
                "@ThriftUnion",
                "public class IntegerUnionId {",
                "  @ThriftUnionId public int id;",
                "  @ThriftField(1) public String value;",
                "}"));
        CompilationResult finalId = compile(source("example.FinalUnionId",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftUnion;",
                "import com.facebook.swift.codec.ThriftUnionId;",
                "@ThriftUnion",
                "public class FinalUnionId {",
                "  @ThriftUnionId public final short id = 0;",
                "  @ThriftField(1) public String value;",
                "}"));

        boxedShort.assertFailedWith("AW5001");
        wrongType.assertFailedWith("AW5001");
        finalId.assertFailedWith("AW3004");
    }

    @Test
    void rejectsUnionPayloadFieldIdZero() {
        CompilationResult result = compile(source("example.ZeroIdUnion",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftUnion;",
                "import com.facebook.swift.codec.ThriftUnionId;",
                "@ThriftUnion",
                "public class ZeroIdUnion {",
                "  @ThriftUnionId public short id;",
                "  @ThriftField(0) public int value;",
                "}"));

        result.assertFailedWith("AW5004");
    }

    @Test
    void boxedVoidIsNotTheSwiftBuilderSentinel() {
        CompilationResult result = compile(source("example.BoxedVoidBuilder",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct(builder=Void.class)",
                "public class BoxedVoidBuilder {",
                "  @ThriftField(1) public String value;",
                "}"));

        result.assertFailedWith("AW3005");
    }

    @Test
    void unionIdMethodMustBeAValidGetter() {
        CompilationResult valid = compile(source("example.UnionIdGetter",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftUnion;",
                "import com.facebook.swift.codec.ThriftUnionId;",
                "@ThriftUnion",
                "public class UnionIdGetter {",
                "  @ThriftUnionId public short getId() { return 1; }",
                "  @ThriftField(1) public String value;",
                "}"));
        CompilationResult invalid = compile(source("example.UnionIdSetter",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftUnion;",
                "import com.facebook.swift.codec.ThriftUnionId;",
                "@ThriftUnion",
                "public class UnionIdSetter {",
                "  @ThriftUnionId public void setId(short id) {}",
                "  @ThriftField(1) public String value;",
                "}"));

        valid.assertSucceeded();
        valid.assertNoThriftAnnotationLintDiagnostics();
        assertFalse(invalid.isSuccessful(), invalid.diagnosticSummary());
        assertTrue(invalid.hasCode("AW5001") || invalid.hasCode("AW3003"), invalid.diagnosticSummary());
        assertFalse(invalid.hasCode("AW9002"), invalid.diagnosticSummary());
        invalid.assertDiagnosticContract();
    }

    @Test
    void validatesEnumValueMethodSignature() {
        CompilationResult valid = compile(source("example.ValidEnum",
                "package example;",
                "import com.facebook.swift.codec.ThriftEnum;",
                "import com.facebook.swift.codec.ThriftEnumValue;",
                "@ThriftEnum",
                "public enum ValidEnum {",
                "  FIRST, SECOND;",
                "  @ThriftEnumValue public int getValue() { return ordinal(); }",
                "}"));
        CompilationResult invalid = compile(source("example.InvalidEnum",
                "package example;",
                "import com.facebook.swift.codec.ThriftEnum;",
                "import com.facebook.swift.codec.ThriftEnumValue;",
                "@ThriftEnum",
                "public enum InvalidEnum {",
                "  FIRST;",
                "  @ThriftEnumValue public static String getValue() { return \"bad\"; }",
                "}"));

        valid.assertSucceeded();
        valid.assertNoThriftAnnotationLintDiagnostics();
        invalid.assertFailedWith("AW6001");
    }

    @Test
    void treatsEnumsAsEnumsBeforeIterableCanonicalChecks() {
        CompilationResult result = compile(
                source("example.IterableEnum",
                        "package example;",
                        "import java.util.Collections;",
                        "import java.util.Iterator;",
                        "public enum IterableEnum implements Iterable<String> {",
                        "  FIRST;",
                        "  public Iterator<String> iterator() {",
                        "    return Collections.<String>emptyList().iterator();",
                        "  }",
                        "}"),
                source("example.IterableEnumHolder",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class IterableEnumHolder {",
                        "  @ThriftField(1) public IterableEnum value;",
                        "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void followsRuntimeEnumMethodInheritanceAndOverrideRules() {
        CompilationResult inherited = compile(
                source("example.EnumValueProvider",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftEnumValue;",
                        "public interface EnumValueProvider {",
                        "  @ThriftEnumValue default int getValue() { return 1; }",
                        "}"),
                source("example.InheritedEnumValue",
                        "package example;",
                        "public enum InheritedEnumValue implements EnumValueProvider { FIRST }"));
        CompilationResult hiddenByOverride = compile(
                source("example.HiddenEnumValueProvider",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftEnumValue;",
                        "public interface HiddenEnumValueProvider {",
                        "  @ThriftEnumValue default long getValue() { return 1L; }",
                        "}"),
                source("example.OverrideEnumValue",
                        "package example;",
                        "public enum OverrideEnumValue implements HiddenEnumValueProvider {",
                        "  FIRST;",
                        "  @Override public long getValue() { return 2L; }",
                        "}"));

        inherited.assertSucceeded();
        inherited.assertNoThriftAnnotationLintDiagnostics();
        hiddenByOverride.assertSucceeded();
        hiddenByOverride.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void validatesInheritedEnumValueMethodsByTheirErasedRuntimeSignature() {
        CompilationResult inherited = compile(
                source("example.GenericEnumValueProvider",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftEnumValue;",
                        "public interface GenericEnumValueProvider<T> {",
                        "  @ThriftEnumValue default T getValue() { return null; }",
                        "}"),
                source("example.GenericInheritedEnumValue",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftEnum;",
                        "@ThriftEnum",
                        "public enum GenericInheritedEnumValue",
                        "    implements GenericEnumValueProvider<Integer> { FIRST }"));
        CompilationResult bridge = compile(
                source("example.BridgeEnumValueProvider",
                        "package example;",
                        "public interface BridgeEnumValueProvider<T> {",
                        "  T getValue();",
                        "}"),
                source("example.BridgeEnumValue",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftEnum;",
                        "import com.facebook.swift.codec.ThriftEnumValue;",
                        "@ThriftEnum",
                        "public enum BridgeEnumValue",
                        "    implements BridgeEnumValueProvider<Integer> {",
                        "  FIRST;",
                        "  @ThriftEnumValue public Integer getValue() { return 1; }",
                        "}"));

        inherited.assertFailedWith("AW6001");
        bridge.assertFailedWith("AW6001");
    }

    @Test
    void rejectsGenericEnumValueMethodsLikeTheOfficialRuntime() {
        CompilationResult result = compile(source("example.GenericValueEnum",
                "package example;",
                "import com.facebook.swift.codec.ThriftEnumValue;",
                "public enum GenericValueEnum {",
                "  FIRST;",
                "  @ThriftEnumValue public <T> int getValue() { return 1; }",
                "}"));

        result.assertFailedWith("AW6001");
    }

    @Test
    void ignoresStaticThriftMethodsDeclaredOnlyOnInterfaces() {
        CompilationResult result = compile(
                source("example.StaticInterfaceField",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "public interface StaticInterfaceField {",
                        "  @ThriftField(9) static String getIgnored() { return \"ignored\"; }",
                        "}"),
                source("example.InterfaceModel",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class InterfaceModel implements StaticInterfaceField {",
                        "  @ThriftField(1) public String value;",
                        "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void parameterOnlyOverrideDoesNotHideAnAnnotatedBaseMethod() {
        CompilationResult result = compile(
                source("example.BaseInjection",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "public class BaseInjection {",
                        "  @ThriftField",
                        "  public void inject(@ThriftField(1) String value) {}",
                        "}"),
                source("example.OverrideInjectionModel",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class OverrideInjectionModel extends BaseInjection {",
                        "  @Override",
                        "  public void inject(@ThriftField(1) String value) {}",
                        "  @ThriftField(1) public String getValue() { return \"\"; }",
                        "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void doesNotInheritAnnotationsAcrossGenericBridgeSignatures() {
        CompilationResult result = compile(
                source("example.GenericWriter",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "public interface GenericWriter<T> {",
                        "  @ThriftField(1) void setValue(T value);",
                        "}"),
                source("example.GenericOverrideModel",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class GenericOverrideModel implements GenericWriter<String> {",
                        "  @Override public void setValue(String value) {}",
                        "  @ThriftField(1) public String getValue() { return \"\"; }",
                        "}"));

        result.assertFailedWith("AW3001");
    }

    @Test
    void stillReportsHiddenInvalidAnnotatedBaseMethods() {
        CompilationResult result = compile(
                source("example.HiddenInvalidBase",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "public class HiddenInvalidBase {",
                        "  @ThriftField(1) private String getValue() { return \"\"; }",
                        "}"),
                source("example.VisibleOverrideModel",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class VisibleOverrideModel extends HiddenInvalidBase {",
                        "  @ThriftField(1) public String getValue() { return \"\"; }",
                        "  @ThriftField(1) public void setValue(String value) {}",
                        "}"));

        result.assertFailedWith("AW3004");
    }

    @Test
    void rejectsGenericUnionIdsBecauseJavaTypeArgumentsCannotBePrimitiveShort() {
        Source union = source("example.GenericUnion",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftUnion;",
                "import com.facebook.swift.codec.ThriftUnionId;",
                "@ThriftUnion",
                "public class GenericUnion<T> {",
                "  @ThriftUnionId public T id;",
                "  @ThriftField(1) public String value;",
                "}");
        CompilationResult boxedShort = compile(
                union,
                source("example.ShortUnionHolder",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class ShortUnionHolder {",
                        "  @ThriftField(1) public GenericUnion<Short> value;",
                        "}"));
        CompilationResult invalid = compile(
                union,
                source("example.IntegerUnionHolder",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class IntegerUnionHolder {",
                        "  @ThriftField(1) public GenericUnion<Integer> value;",
                        "}"));

        boxedShort.assertFailedWith("AW5001");
        invalid.assertFailedWith("AW5001");
    }

    @Test
    void rejectsEnumValueAnnotationOnAnOrdinaryClass() {
        CompilationResult result = compile(source("example.NotAnEnum",
                "package example;",
                "import com.facebook.swift.codec.ThriftEnumValue;",
                "public class NotAnEnum {",
                "  @ThriftEnumValue public int getValue() { return 1; }",
                "}"));

        result.assertFailedWith("AW6001");
    }

    @Test
    void requiresStableIdentityForSourceInjectionParameters() {
        CompilationResult result = compile(source("example.ImplicitConstructorName",
                "package example;",
                "import com.facebook.swift.codec.ThriftConstructor;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class ImplicitConstructorName {",
                "  @ThriftConstructor",
                "  public ImplicitConstructorName(@ThriftField String value) {}",
                "  @ThriftField(1) public String getValue() { return \"\"; }",
                "}"));

        result.assertFailedWith("AW3003");
    }

    @Test
    void keepsExecutableTypeVariablesDistinctInDemandCacheKeys() {
        CompilationResult result = compile(source("example.ScopedTypeVariables",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class ScopedTypeVariables {",
                "  @ThriftField(1)",
                "  public <T extends Value> Box<T> getBounded() { return null; }",
                "  @ThriftField(2)",
                "  public <T> Box<T> getUnbounded() { return null; }",
                "  @ThriftStruct public static class Box<X> {",
                "    @ThriftField(1) public X value;",
                "  }",
                "  @ThriftStruct public static class Value {",
                "    @ThriftField(1) public String value;",
                "  }",
                "}"));

        result.assertFailedWith("AW4001");
    }

    @Test
    void distinguishesOverloadedExecutableTypeVariablesByErasedSignature() {
        CompilationResult result = compile(source("example.OverloadedTypeVariables",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class OverloadedTypeVariables {",
                "  @ThriftField public <T extends String> void inject(",
                "      @ThriftField(value=1, name=\"stringBox\") Box<T> box,",
                "      @ThriftField(value=2, name=\"stringValue\") T value) {}",
                "  @ThriftField public <T extends Integer> void inject(",
                "      @ThriftField(value=3, name=\"integerBox\") Box<T> box,",
                "      @ThriftField(value=4, name=\"integerValue\") T value) {}",
                "  @ThriftField(value=1, name=\"stringBox\")",
                "  public Box getStringBox() { return null; }",
                "  @ThriftField(value=2, name=\"stringValue\")",
                "  public String getStringValue() { return null; }",
                "  @ThriftField(value=3, name=\"integerBox\")",
                "  public Box getIntegerBox() { return null; }",
                "  @ThriftField(value=4, name=\"integerValue\")",
                "  public Integer getIntegerValue() { return null; }",
                "  @ThriftStruct public static class Box<X> {",
                "    @ThriftField(1) public X getValue() { return null; }",
                "    @ThriftField(1) public void setValue(String value) {}",
                "  }",
                "}"));

        result.assertFailedWith("AW4002");
    }

    @Test
    void permitsFiniteGenericExpansionBeforeTheExactCacheCloses() {
        CompilationResult result = compile(source("example.FiniteGenericClosure",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "import java.util.List;",
                "import static com.facebook.swift.codec.ThriftField.Recursiveness.TRUE;",
                "import static com.facebook.swift.codec.ThriftField.Requiredness.OPTIONAL;",
                "@ThriftStruct",
                "public class FiniteGenericClosure {",
                "  @ThriftField(1) public A<Integer, Integer, Integer, Integer> value;",
                "  @ThriftStruct public static class A<W, X, Y, Z> {",
                "    @ThriftField(value=1, requiredness=OPTIONAL, isRecursive=TRUE)",
                "    public A<X, Y, List<Z>, String> next;",
                "  }",
                "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void strictAndWarningModesChangeOnlyModelDiagnosticSeverity() {
        Source source = source("example.ModeValue",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class ModeValue {",
                "  @ThriftField(1) public String first;",
                "  @ThriftField(1) public String second;",
                "}");

        CompilationResult strict = compile(source);
        CompilationResult warning = compileWithOptions(
                Collections.singletonList("-Athrift.annotation.lint.mode=warning"), source);

        strict.assertFailedWith("AW2002");
        assertEquals(Diagnostic.Kind.ERROR, strict.diagnostic("AW2002").getKind());
        warning.assertSucceeded();
        assertEquals(Diagnostic.Kind.WARNING, warning.diagnostic("AW2002").getKind());
    }

    @Test
    void conflictingExplicitNamesHonorStrictAndWarningModes() {
        Source source = source("example.ConflictingNamesOnly",
                "package example;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class ConflictingNamesOnly {",
                "  @ThriftField(value=1, name=\"first\")",
                "  public String getValue() { return \"\"; }",
                "  @ThriftField(value=1, name=\"second\")",
                "  public void setValue(String value) {}",
                "}");

        CompilationResult strict = compile(source);
        CompilationResult warning = compileWithOptions(
                Collections.singletonList("-Athrift.annotation.lint.mode=warning"), source);

        strict.assertFailedWith("AW2004");
        assertEquals(Diagnostic.Kind.ERROR, strict.diagnostic("AW2004").getKind());
        warning.assertSucceeded();
        assertEquals(Diagnostic.Kind.WARNING, warning.diagnostic("AW2004").getKind());
    }

    @Test
    void invalidModeAlwaysFailsCompilation() {
        CompilationResult result = compileWithOptions(
                Collections.singletonList("-Athrift.annotation.lint.mode=verbose"),
                source("example.ValidValue",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class ValidValue {",
                        "  @ThriftField(1) public String value;",
                        "}"));

        result.assertFailedWith("AW9001");
        assertEquals(Diagnostic.Kind.ERROR, result.diagnostic("AW9001").getKind());
    }

    @Test
    void exactModelLimitProtectsDemandExpansionButDoesNotCountSourceRoots() {
        CompilationResult independentRoots = compileWithOptions(
                Collections.singletonList("-Athrift.annotation.lint.maxExactModels=1"),
                source("example.FirstRoot",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct public class FirstRoot {",
                        "  @ThriftField(1) public String value;",
                        "}"),
                source("example.SecondRoot",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct public class SecondRoot {",
                        "  @ThriftField(1) public String value;",
                        "}"));
        CompilationResult boundedDemand = compileWithOptions(
                Collections.singletonList("-Athrift.annotation.lint.maxExactModels=1"),
                source("example.DemandLimit",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct public class DemandLimit {",
                        "  @ThriftField(1) public <T extends Box> T getFirst() { return null; }",
                        "  @ThriftField(2) public <U extends Box> U getSecond() { return null; }",
                        "  @ThriftStruct public static class Box {",
                        "    @ThriftField(1) public String value;",
                        "  }",
                        "}"));

        independentRoots.assertSucceeded();
        independentRoots.assertNoThriftAnnotationLintDiagnostics();
        boundedDemand.assertFailedWith("AW9003");
    }

    @Test
    void exactModelLimitIsCompilationWideAcrossGeneratedRounds() {
        CompilationResult result = compileWithOptionsAndAdditionalProcessor(
                Collections.singletonList("-Athrift.annotation.lint.maxExactModels=1"),
                new ChainedBudgetGenerator(),
                source("example.BudgetBox",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct public class BudgetBox<T> {",
                        "  @ThriftField(1) public T value;",
                        "}"));

        result.assertFailedWith("AW9003");
    }

    @Test
    void unresolvedContainerDemandsDoNotConsumeTheResolvedModelBudget() {
        CompilationResult result = compileWithOptionsAndAdditionalProcessor(
                Collections.singletonList("-Athrift.annotation.lint.maxExactModels=1"),
                new GeneratedValueGenerator(),
                source("example.GeneratedContainerBudgetRoot",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "import java.util.ArrayList;",
                        "@ThriftStruct",
                        "public class GeneratedContainerBudgetRoot",
                        "    extends ArrayList<BudgetBox<GeneratedValue>> {}"),
                source("example.BudgetBox",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct public class BudgetBox<T> {",
                        "  @ThriftField(1) public T value;",
                        "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void lateContainerMigrationDoesNotReserveAResolvedModelBudgetSlot() {
        CompilationResult result = compileWithOptionsAndAdditionalProcessor(
                Collections.singletonList("-Athrift.annotation.lint.maxExactModels=1"),
                new SourceGenerator(
                        "example.GeneratedGenericList",
                        "package example;\n"
                                + "public class GeneratedGenericList<T> "
                                + "extends java.util.ArrayList<T> {}\n"),
                source("example.ALateContainerBudgetRoot",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "import java.util.ArrayList;",
                        "@ThriftStruct",
                        "public class ALateContainerBudgetRoot",
                        "    extends ArrayList<LateBudgetBox<String>> {}"),
                source("example.BResolvedModelBudgetRoot",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "import java.util.ArrayList;",
                        "@ThriftStruct",
                        "public class BResolvedModelBudgetRoot",
                        "    extends ArrayList<RealBudgetBox<String>> {}"),
                source("example.LateBudgetBox",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class LateBudgetBox<T> extends GeneratedGenericList<T> {}"),
                source("example.RealBudgetBox",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct public class RealBudgetBox<T> {",
                        "  @ThriftField(1) public T value;",
                        "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void generatedContainerPriorityReplacesRatherThanAccumulatesExactDemands() {
        CompilationResult result = compileWithOptionsAndAdditionalProcessor(
                Collections.singletonList("-Athrift.annotation.lint.maxExactModels=1"),
                new SourceGenerator(
                        "example.GeneratedDemandMapBase",
                        "package example;\n"
                                + "public class GeneratedDemandMapBase "
                                + "extends java.util.HashMap<String, NewDemandBox<String>> {}\n"),
                source("example.GeneratedDemandContainerRoot",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public abstract class GeneratedDemandContainerRoot",
                        "    extends GeneratedDemandMapBase",
                        "    implements Iterable<OldDemandBox<String>> {}"),
                source("example.OldDemandBox",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct public class OldDemandBox<T> {",
                        "  @ThriftField(1) public T value;",
                        "}"),
                source("example.NewDemandBox",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct public class NewDemandBox<T> {",
                        "  @ThriftField(1) public T value;",
                        "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void doesNotTreatUnannotatedLombokAccessorsAsSwiftInjectionPaths() {
        CompilationResult result = compile(
                source("lombok.Data",
                        "package lombok;",
                        "import java.lang.annotation.ElementType;",
                        "import java.lang.annotation.Target;",
                        "@Target(ElementType.TYPE)",
                        "public @interface Data {}"),
                source("example.LombokValue",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "import lombok.Data;",
                        "@Data",
                        "@ThriftStruct",
                        "public class LombokValue {",
                        "  private String name;",
                        "  @ThriftField(1)",
                        "  public String getName() { return name; }",
                        "}"));

        result.assertFailedWith("AW3001");
    }

    @Test
    void allProcessorDiagnosticsUseEnglishAndCarrySourceLocations() {
        CompilationResult result = compile(
                source("example.BadStruct",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "class BadStruct {",
                        "  @ThriftField public Object first;",
                        "  @ThriftField(1) public String second;",
                        "  @ThriftField(1) public Integer third;",
                        "  @ThriftField(2) public void setBroken() {}",
                        "}"),
                source("example.BadUnion",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftUnion;",
                        "@ThriftUnion",
                        "public class BadUnion {",
                        "  @ThriftField(1) public String value;",
                        "}"));

        assertFalse(result.isSuccessful(), result.diagnosticSummary());
        assertTrue(result.thriftAnnotationLintDiagnostics().size() >= 4, result.diagnosticSummary());
        result.assertDiagnosticContract();
        for (Diagnostic<? extends JavaFileObject> diagnostic : result.thriftAnnotationLintDiagnostics()) {
            assertFalse(diagnostic.getMessage(java.util.Locale.ENGLISH).contains("null"),
                    "Diagnostic must not expose a null placeholder: " + diagnostic.getMessage(java.util.Locale.ENGLISH));
        }
    }

    private static final class RoundBGenerator extends AbstractProcessor {
        private boolean generated;

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
            if (generated || roundEnvironment.processingOver()) {
                return false;
            }
            generated = true;
            try {
                Writer writer = processingEnv.getFiler()
                        .createSourceFile("example.RoundB")
                        .openWriter();
                try {
                    writer.write("package example;\n"
                            + "import com.facebook.swift.codec.ThriftField;\n"
                            + "import com.facebook.swift.codec.ThriftStruct;\n"
                            + "@ThriftStruct\n"
                            + "public class RoundB {\n"
                            + "  @ThriftField(1) public RoundA value;\n"
                            + "}\n");
                }
                finally {
                    writer.close();
                }
            }
            catch (IOException failure) {
                throw new IllegalStateException("Could not generate round fixture", failure);
            }
            return false;
        }
    }

    private static final class ChainedBudgetGenerator extends AbstractProcessor {
        private int generatedCount;

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
            if (generatedCount >= 2 || roundEnvironment.processingOver()) {
                return false;
            }
            generatedCount++;
            String suffix = generatedCount == 1 ? "One" : "Two";
            String argument = generatedCount == 1 ? "String" : "Integer";
            try {
                Writer writer = processingEnv.getFiler()
                        .createSourceFile("example.GeneratedBudget" + suffix)
                        .openWriter();
                try {
                    writer.write("package example;\n"
                            + "import com.facebook.swift.codec.ThriftField;\n"
                            + "import com.facebook.swift.codec.ThriftStruct;\n"
                            + "@ThriftStruct public class GeneratedBudget" + suffix + " {\n"
                            + "  @ThriftField(1) public BudgetBox<" + argument
                            + "> value;\n"
                            + "}\n");
                }
                finally {
                    writer.close();
                }
            }
            catch (IOException failure) {
                throw new IllegalStateException(
                        "Could not generate exact-budget round fixture", failure);
            }
            return false;
        }
    }

    private static final class GeneratedValueGenerator extends AbstractProcessor {
        private boolean generated;

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
            if (generated || roundEnvironment.processingOver()) {
                return false;
            }
            generated = true;
            try {
                Writer writer = processingEnv.getFiler()
                        .createSourceFile("example.GeneratedValue")
                        .openWriter();
                try {
                    writer.write("package example;\n"
                            + "import com.facebook.swift.codec.ThriftField;\n"
                            + "import com.facebook.swift.codec.ThriftStruct;\n"
                            + "@ThriftStruct\n"
                            + "public class GeneratedValue {\n"
                            + "  @ThriftField(1) public String value;\n"
                            + "}\n");
                }
                finally {
                    writer.close();
                }
            }
            catch (IOException failure) {
                throw new IllegalStateException("Could not generate value fixture", failure);
            }
            return false;
        }
    }

    private static final class DelayedGeneratedValueGenerator extends AbstractProcessor {
        private int generatedCount;

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
            if (generatedCount >= 2 || roundEnvironment.processingOver()) {
                return false;
            }
            generatedCount++;
            String className = generatedCount == 1
                    ? "example.MixedRoundTrigger"
                    : "example.LateGenerated";
            try {
                Writer writer = processingEnv.getFiler().createSourceFile(className).openWriter();
                try {
                    writer.write("package example;\n"
                            + "import com.facebook.swift.codec.ThriftField;\n"
                            + "import com.facebook.swift.codec.ThriftStruct;\n"
                            + "@ThriftStruct\n"
                            + "public class "
                            + (generatedCount == 1 ? "MixedRoundTrigger" : "LateGenerated")
                            + " {\n"
                            + "  @ThriftField(1) public String value;\n"
                            + "}\n");
                }
                finally {
                    writer.close();
                }
            }
            catch (IOException failure) {
                throw new IllegalStateException(
                        "Could not generate the mixed exact-instance fixture", failure);
            }
            return false;
        }
    }

    private static final class SourceGenerator extends AbstractProcessor {
        private final String className;
        private final String source;
        private boolean generated;

        private SourceGenerator(String className, String source) {
            this.className = className;
            this.source = source;
        }

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
            if (generated || roundEnvironment.processingOver()) {
                return false;
            }
            generated = true;
            try {
                Writer writer = processingEnv.getFiler().createSourceFile(className).openWriter();
                try {
                    writer.write(source);
                }
                finally {
                    writer.close();
                }
            }
            catch (IOException failure) {
                throw new IllegalStateException("Could not generate source fixture", failure);
            }
            return false;
        }
    }
}
