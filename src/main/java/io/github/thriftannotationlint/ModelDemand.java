package io.github.thriftannotationlint;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/** Immutable request to validate one exact Swift model instance. */
final class ModelDemand {
    final TypeElement type;
    final DeclaredType declaredType;
    final TypeMirror requestedType;
    final String identity;
    final SwiftModel.Kind kind;
    final Element diagnosticAnchor;
    final DemandPath path;
    final boolean forceRevalidation;

    ModelDemand(
            TypeElement type,
            DeclaredType declaredType,
            TypeMirror requestedType,
            String identity,
            SwiftModel.Kind kind,
            Element diagnosticAnchor,
            DemandPath path,
            boolean forceRevalidation) {
        this.type = type;
        this.declaredType = declaredType;
        this.requestedType = requestedType;
        this.identity = identity;
        this.kind = kind;
        this.diagnosticAnchor = diagnosticAnchor;
        this.path = path;
        this.forceRevalidation = forceRevalidation;
    }
}
