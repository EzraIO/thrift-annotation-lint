package io.github.thriftannotationlint;

import org.junit.jupiter.api.Test;

import javax.annotation.processing.Processor;
import java.util.Locale;

import static io.github.thriftannotationlint.CompilerTestSupport.compileWithPrecedingProcessor;
import static io.github.thriftannotationlint.CompilerTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LombokCompatibilityTest {
    @Test
    void acceptsAllDialectsOnLombokGeneratedPublicAccessors() {
        CompilerTestSupport.CompilationResult result = compileWithPrecedingProcessor(
                lombokProcessor(),
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

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void rejectsPrivateAnnotatedFieldEvenWhenLombokGeneratesAccessors() {
        CompilerTestSupport.CompilationResult result = compileWithPrecedingProcessor(
                lombokProcessor(),
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

        result.assertFailedWith("AW3004");
        assertTrue(
                result.diagnostic("AW3004").getMessage(Locale.ENGLISH)
                        .contains("Lombok-generated public accessors using field-level "
                                + "@Getter/@Setter(onMethod_)"),
                result.diagnosticSummary());
    }

    private Processor lombokProcessor() {
        try {
            Class<?> processorType = Class.forName(
                    "lombok.launch.AnnotationProcessorHider$AnnotationProcessor");
            return (Processor) processorType.getDeclaredConstructor().newInstance();
        }
        catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not create the Lombok annotation processor", e);
        }
    }
}
