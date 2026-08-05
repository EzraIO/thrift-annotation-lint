package io.github.thriftannotationlint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the runtime facts behind dialect-specific enum and recursion rules. */
final class OfficialDialectRuntimeParityTest {
    @Test
    void driftRuntimesRequireEnumModelAnnotationsWhileSwiftAcceptsPlainEnums() {
        assertNotNull(new com.facebook.swift.codec.metadata.ThriftCatalog()
                .getThriftType(PlainSwiftEnum.class));
        assertThrows(
                RuntimeException.class,
                () -> new io.airlift.drift.codec.metadata.ThriftCatalog()
                        .getThriftType(PlainAirliftDriftEnum.class));
        assertThrows(
                RuntimeException.class,
                () -> new com.facebook.drift.codec.metadata.ThriftCatalog()
                        .getThriftType(PlainPrestoDriftEnum.class));
    }

    @Test
    void driftRuntimesRecognizeTheirRecursiveReferenceIdlKey() {
        assertTrue(new io.airlift.drift.codec.metadata.ThriftCatalog()
                .getThriftStructMetadata(AirliftRecursiveNode.class)
                .getField(1)
                .isRecursiveReference());
        assertTrue(new com.facebook.drift.codec.metadata.ThriftCatalog()
                .getThriftStructMetadata(PrestoRecursiveNode.class)
                .getField(1)
                .isRecursiveReference());
    }

    public enum PlainSwiftEnum {
        READY
    }

    public enum PlainAirliftDriftEnum {
        READY;

        @io.airlift.drift.annotations.ThriftEnumValue
        public int value() {
            return ordinal();
        }
    }

    public enum PlainPrestoDriftEnum {
        READY;

        @com.facebook.drift.annotations.ThriftEnumValue
        public int value() {
            return ordinal();
        }
    }

    @io.airlift.drift.annotations.ThriftStruct
    public static class AirliftRecursiveNode {
        @io.airlift.drift.annotations.ThriftField(
                value = 1,
                requiredness = io.airlift.drift.annotations.ThriftField.Requiredness.OPTIONAL,
                idlAnnotations = @io.airlift.drift.annotations.ThriftIdlAnnotation(
                        key = "drift.recursive_reference",
                        value = "true"))
        public AirliftRecursiveNode next;
    }

    @com.facebook.drift.annotations.ThriftStruct
    public static class PrestoRecursiveNode {
        @com.facebook.drift.annotations.ThriftField(
                value = 1,
                requiredness = com.facebook.drift.annotations.ThriftField.Requiredness.OPTIONAL,
                idlAnnotations = @com.facebook.drift.annotations.ThriftIdlAnnotation(
                        key = "drift.recursive_reference",
                        value = "true"))
        public PrestoRecursiveNode next;
    }
}
