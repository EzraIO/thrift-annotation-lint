package io.github.thriftannotationlint;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/** Annotated source root that Swift classifies as a container instead of a model. */
final class ContainerDemand {
    final TypeElement element;
    final TypeMirror classificationType;

    ContainerDemand(TypeElement element, TypeMirror classificationType) {
        this.element = element;
        this.classificationType = classificationType;
    }
}
