package io.github.thriftannotationlint.internal.planning;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/** Annotated source root that Swift classifies as a container instead of a model. */
public final class ContainerDemand {
    final TypeElement element;
    final TypeMirror classificationType;

    public ContainerDemand(TypeElement element, TypeMirror classificationType) {
        this.element = element;
        this.classificationType = classificationType;
    }

    public TypeElement element() {
        return element;
    }

    public TypeMirror classificationType() {
        return classificationType;
    }
}
