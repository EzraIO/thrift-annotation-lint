package io.github.thriftannotationlint.internal.model;

import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/** Reachable model view paired with the exact runtime type requested by Swift. */
public final class ModelReference {
    private final TypeMirror requestedType;
    private final DeclaredType modelView;

    public ModelReference(TypeMirror requestedType, DeclaredType modelView) {
        this.requestedType = requestedType;
        this.modelView = modelView;
    }

    public TypeMirror requestedType() {
        return requestedType;
    }

    public DeclaredType modelView() {
        return modelView;
    }
}
