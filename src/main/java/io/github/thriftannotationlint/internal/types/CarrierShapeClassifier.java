package io.github.thriftannotationlint.internal.types;

import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;

import javax.lang.model.type.TypeMirror;

/** Preserves Java carrier wrappers that wire normalization intentionally collapses. */
final class CarrierShapeClassifier {
    private final WireTypeClassifier classifier;

    CarrierShapeClassifier(WireTypeClassifier classifier) {
        this.classifier = classifier;
    }

    String classify(TypeMirror type, ThriftAnnotationDialect dialect) {
        return classifier.carrierShape(type, dialect);
    }
}
