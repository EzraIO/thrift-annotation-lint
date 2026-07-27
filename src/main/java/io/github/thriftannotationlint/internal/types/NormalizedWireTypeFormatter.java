package io.github.thriftannotationlint.internal.types;

import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;

import javax.lang.model.type.TypeMirror;

/** Produces dialect-aware normalized wire identities. */
final class NormalizedWireTypeFormatter {
    private final WireTypeClassifier classifier;

    NormalizedWireTypeFormatter(WireTypeClassifier classifier) {
        this.classifier = classifier;
    }

    String format(TypeMirror type, ThriftAnnotationDialect dialect) {
        return classifier.normalizedType(type, dialect);
    }
}
