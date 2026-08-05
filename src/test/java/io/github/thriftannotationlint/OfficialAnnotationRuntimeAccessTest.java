package io.github.thriftannotationlint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies field visibility against each supported official reflection metadata runtime. */
final class OfficialAnnotationRuntimeAccessTest {
    @Test
    void officialRuntimesRejectPrivateAnnotatedFieldsDespitePublicAccessors() {
        assertThrows(
                RuntimeException.class,
                () -> new com.facebook.swift.codec.metadata.ThriftCatalog()
                        .getThriftStructMetadata(InvalidSwiftValue.class));
        assertThrows(
                RuntimeException.class,
                () -> new io.airlift.drift.codec.metadata.ThriftCatalog()
                        .getThriftStructMetadata(InvalidAirliftDriftValue.class));
        assertThrows(
                RuntimeException.class,
                () -> new com.facebook.drift.codec.metadata.ThriftCatalog()
                        .getThriftStructMetadata(InvalidPrestoDriftValue.class));
    }

    @Test
    void officialRuntimesAcceptPrivateStorageBehindAnnotatedPublicAccessors() {
        assertNotNull(new com.facebook.swift.codec.metadata.ThriftCatalog()
                .getThriftStructMetadata(ValidSwiftValue.class).getField(1));
        assertNotNull(new io.airlift.drift.codec.metadata.ThriftCatalog()
                .getThriftStructMetadata(ValidAirliftDriftValue.class).getField(1));
        assertNotNull(new com.facebook.drift.codec.metadata.ThriftCatalog()
                .getThriftStructMetadata(ValidPrestoDriftValue.class).getField(1));
    }

    @com.facebook.swift.codec.ThriftStruct
    public static class InvalidSwiftValue {
        @com.facebook.swift.codec.ThriftField(1)
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @io.airlift.drift.annotations.ThriftStruct
    public static class InvalidAirliftDriftValue {
        @io.airlift.drift.annotations.ThriftField(1)
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @com.facebook.drift.annotations.ThriftStruct
    public static class InvalidPrestoDriftValue {
        @com.facebook.drift.annotations.ThriftField(1)
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @com.facebook.swift.codec.ThriftStruct
    public static class ValidSwiftValue {
        private String name;

        @com.facebook.swift.codec.ThriftField(1)
        public String getName() {
            return name;
        }

        @com.facebook.swift.codec.ThriftField
        public void setName(String name) {
            this.name = name;
        }
    }

    @io.airlift.drift.annotations.ThriftStruct
    public static class ValidAirliftDriftValue {
        private String name;

        @io.airlift.drift.annotations.ThriftField(1)
        public String getName() {
            return name;
        }

        @io.airlift.drift.annotations.ThriftField
        public void setName(String name) {
            this.name = name;
        }
    }

    @com.facebook.drift.annotations.ThriftStruct
    public static class ValidPrestoDriftValue {
        private String name;

        @com.facebook.drift.annotations.ThriftField(1)
        public String getName() {
            return name;
        }

        @com.facebook.drift.annotations.ThriftField
        public void setName(String name) {
            this.name = name;
        }
    }
}
