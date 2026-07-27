package io.github.thriftannotationlint.internal.types;

import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;

/** Codec-specific type capabilities layered over the shared wire classifier. */
final class DialectTypePolicy {
    boolean supportsOptional(ThriftAnnotationDialect dialect) {
        return dialect == ThriftAnnotationDialect.AIRLIFT_DRIFT;
    }
}
