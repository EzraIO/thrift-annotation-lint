package io.github.thriftannotationlint.internal.validation;

import io.github.thriftannotationlint.internal.model.ResolvedLogicalFields;
import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.types.ThriftTypeInspector;

/** Immutable shared inputs for rules that validate one resolved model. */
final class LogicalFieldValidationContext {
    private final SwiftModel model;
    private final ResolvedLogicalFields resolvedFields;
    private final ThriftTypeInspector typeInspector;

    LogicalFieldValidationContext(
            SwiftModel model,
            ResolvedLogicalFields resolvedFields,
            ThriftTypeInspector typeInspector) {
        this.model = model;
        this.resolvedFields = resolvedFields;
        this.typeInspector = typeInspector;
    }

    SwiftModel model() {
        return model;
    }

    ResolvedLogicalFields.IdResolution idResolution() {
        return resolvedFields.idResolution();
    }

    ThriftTypeInspector typeInspector() {
        return typeInspector;
    }
}
