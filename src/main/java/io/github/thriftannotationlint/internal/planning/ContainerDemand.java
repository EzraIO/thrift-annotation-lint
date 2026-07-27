package io.github.thriftannotationlint.internal.planning;

import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/** Annotated source root that Swift classifies as a container instead of a model. */
public final class ContainerDemand {
    final TypeElement element;
    final TypeMirror classificationType;
    final ThriftAnnotationDialect dialect;

    public ContainerDemand(
            TypeElement element,
            TypeMirror classificationType,
            ThriftAnnotationDialect dialect) {
        this.element = element;
        this.classificationType = classificationType;
        this.dialect = dialect;
    }

    public TypeElement element() {
        return element;
    }

    public TypeMirror classificationType() {
        return classificationType;
    }

    public ThriftAnnotationDialect dialect() {
        return dialect;
    }
}
