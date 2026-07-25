package io.github.thriftannotationlint;

import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/** Reachable model view paired with the exact runtime type requested by Swift. */
final class ModelReference {
    final TypeMirror requestedType;
    final DeclaredType modelView;

    ModelReference(TypeMirror requestedType, DeclaredType modelView) {
        this.requestedType = requestedType;
        this.modelView = modelView;
    }
}
