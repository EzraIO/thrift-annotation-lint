package io.github.thriftannotationlint.internal.extract;

import io.github.thriftannotationlint.internal.model.ThriftAnnotations;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;

import io.github.thriftannotationlint.internal.model.SwiftModel;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

/** Centralizes Java declaration precedence for Swift models. */
public final class SwiftModelClassifier {
    public SwiftModel.Kind modelKind(TypeElement type) {
        if (type.getKind() == ElementKind.ENUM) {
            return SwiftModel.Kind.ENUM;
        }
        for (ThriftAnnotationDialect dialect : ThriftAnnotationDialect.values()) {
            if (ThriftAnnotations.has(type, dialect.thriftStruct())) {
                return SwiftModel.Kind.STRUCT;
            }
            if (ThriftAnnotations.has(type, dialect.thriftUnion())) {
                return SwiftModel.Kind.UNION;
            }
        }
        return null;
    }

    public int priority(SwiftModel.Kind kind) {
        if (kind == SwiftModel.Kind.STRUCT) {
            return 0;
        }
        if (kind == SwiftModel.Kind.UNION) {
            return 1;
        }
        return 2;
    }
}
