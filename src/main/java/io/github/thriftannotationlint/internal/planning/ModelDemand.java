package io.github.thriftannotationlint.internal.planning;

import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/** Immutable request to validate one exact Swift model instance. */
public final class ModelDemand {
    final TypeElement type;
    final DeclaredType declaredType;
    final TypeMirror requestedType;
    final String identity;
    final ThriftAnnotationDialect dialect;
    final SwiftModel.Kind kind;
    final Element diagnosticAnchor;
    final DemandPath path;
    final boolean forceRevalidation;

    ModelDemand(
            TypeElement type,
            DeclaredType declaredType,
            TypeMirror requestedType,
            String identity,
            ThriftAnnotationDialect dialect,
            SwiftModel.Kind kind,
            Element diagnosticAnchor,
            DemandPath path,
            boolean forceRevalidation) {
        this.type = type;
        this.declaredType = declaredType;
        this.requestedType = requestedType;
        this.identity = identity;
        this.dialect = dialect;
        this.kind = kind;
        this.diagnosticAnchor = diagnosticAnchor;
        this.path = path;
        this.forceRevalidation = forceRevalidation;
    }

    public TypeElement type() {
        return type;
    }

    public DeclaredType declaredType() {
        return declaredType;
    }

    public TypeMirror requestedType() {
        return requestedType;
    }

    public String identity() {
        return identity;
    }

    public String cacheKey() {
        return dialect.name() + "\u0000" + identity;
    }

    public ThriftAnnotationDialect dialect() {
        return dialect;
    }

    public SwiftModel.Kind kind() {
        return kind;
    }

    public Element diagnosticAnchor() {
        return diagnosticAnchor;
    }

    public boolean forceRevalidation() {
        return forceRevalidation;
    }
}
