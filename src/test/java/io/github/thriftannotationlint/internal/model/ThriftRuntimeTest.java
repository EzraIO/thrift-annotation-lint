package io.github.thriftannotationlint.internal.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ThriftRuntimeTest {
    @Test
    void dialectsShareOnlyTheirDeclaredRuntimeCapabilities() {
        assertSame(ThriftRuntime.SWIFT, ThriftAnnotationDialect.FACEBOOK_SWIFT.runtime());
        assertSame(ThriftRuntime.DRIFT, ThriftAnnotationDialect.AIRLIFT_DRIFT.runtime());
        assertSame(ThriftRuntime.DRIFT, ThriftAnnotationDialect.PRESTODB_DRIFT.runtime());

        assertFalse(ThriftRuntime.SWIFT.supportsOptionalCarriers());
        assertFalse(ThriftRuntime.SWIFT.enumPolicy().requiresModelAnnotation());
        assertFalse(ThriftRuntime.SWIFT.enumPolicy().requiresValueMethod());
        assertFalse(ThriftRuntime.SWIFT.enumPolicy().supportsUnknownValue());
        assertFalse(ThriftRuntime.SWIFT.parameterNameStrategy().prefersMethodParameters());
        assertTrue(ThriftRuntime.SWIFT.parameterNameStrategy().supportsJavaxInjectNamed());
        assertFalse(ThriftRuntime.SWIFT.parameterNameStrategy().fallsBackFromInvalidBytecode());
        org.junit.jupiter.api.Assertions.assertEquals(
                "swift.recursive_reference",
                ThriftRuntime.SWIFT.recursiveReferenceIdlKey());

        assertTrue(ThriftRuntime.DRIFT.supportsOptionalCarriers());
        assertTrue(ThriftRuntime.DRIFT.enumPolicy().requiresModelAnnotation());
        assertTrue(ThriftRuntime.DRIFT.enumPolicy().requiresValueMethod());
        assertTrue(ThriftRuntime.DRIFT.enumPolicy().supportsUnknownValue());
        assertTrue(ThriftRuntime.DRIFT.parameterNameStrategy().prefersMethodParameters());
        assertFalse(ThriftRuntime.DRIFT.parameterNameStrategy().supportsJavaxInjectNamed());
        assertTrue(ThriftRuntime.DRIFT.parameterNameStrategy().fallsBackFromInvalidBytecode());
        org.junit.jupiter.api.Assertions.assertEquals(
                "drift.recursive_reference",
                ThriftRuntime.DRIFT.recursiveReferenceIdlKey());
    }
}
