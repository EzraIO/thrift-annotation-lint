package io.github.thriftannotationlint.internal.extract;

import io.github.thriftannotationlint.internal.model.SwiftAnnotations;

import io.github.thriftannotationlint.internal.model.SwiftModel;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

/** Centralizes Java declaration precedence for Swift models. */
public final class SwiftModelClassifier {
    public SwiftModel.Kind modelKind(TypeElement type) {
        if (type.getKind() == ElementKind.ENUM) {
            return SwiftModel.Kind.ENUM;
        }
        if (SwiftAnnotations.has(type, SwiftAnnotations.THRIFT_STRUCT)) {
            return SwiftModel.Kind.STRUCT;
        }
        if (SwiftAnnotations.has(type, SwiftAnnotations.THRIFT_UNION)) {
            return SwiftModel.Kind.UNION;
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
