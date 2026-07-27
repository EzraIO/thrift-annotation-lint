package io.github.thriftannotationlint.internal.validation;

import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.model.ResolvedLogicalFields;
import io.github.thriftannotationlint.internal.model.SwiftModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Round-scoped immutable validation data reused by graph-wide rules. */
public final class ModelValidation {
    private final SwiftModel model;
    private final ResolvedLogicalFields resolvedFields;
    private final List<Finding> findings;

    public ModelValidation(
            SwiftModel model,
            ResolvedLogicalFields resolvedFields,
            List<Finding> findings) {
        this.model = model;
        this.resolvedFields = resolvedFields;
        this.findings = Collections.unmodifiableList(new ArrayList<Finding>(findings));
    }

    public SwiftModel model() {
        return model;
    }

    public ResolvedLogicalFields resolvedFields() {
        return resolvedFields;
    }

    public List<Finding> findings() {
        return findings;
    }
}
