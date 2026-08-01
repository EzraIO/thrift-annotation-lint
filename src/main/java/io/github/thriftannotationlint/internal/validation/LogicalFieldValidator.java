package io.github.thriftannotationlint.internal.validation;

import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.model.ResolvedLogicalFields;
import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.types.ThriftTypeInspector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Applies rules that operate on already-resolved logical fields. */
final class LogicalFieldValidator {
    private final LogicalFieldMetadataRules metadataRules = new LogicalFieldMetadataRules();
    private final LogicalFieldTypeRules typeRules = new LogicalFieldTypeRules();
    private final LogicalFieldAccessRules accessRules = new LogicalFieldAccessRules();
    private final LogicalFieldIdRules idRules = new LogicalFieldIdRules();
    private final ThriftTypeInspector typeInspector;

    LogicalFieldValidator(ThriftTypeInspector typeInspector) {
        this.typeInspector = typeInspector;
    }

    List<Finding> validate(SwiftModel model, ResolvedLogicalFields resolvedFields) {
        List<Finding> findings = new ArrayList<Finding>();
        LogicalFieldValidationContext context = new LogicalFieldValidationContext(
                model, resolvedFields, typeInspector);
        Map<Short, List<ResolvedLogicalFields.LogicalField>> fieldsById =
                new LinkedHashMap<Short, List<ResolvedLogicalFields.LogicalField>>();

        for (ResolvedLogicalFields.LogicalField field : resolvedFields.fields()) {
            metadataRules.validate(context, field, findings);
            typeRules.validate(context, field, findings);
            accessRules.validateRuntimeSelection(context, field, findings);
            if (!field.hasUnreliableIdentity()) {
                accessRules.validateAccessPaths(context, field, findings);
            }
            idRules.validateField(context, field, fieldsById, findings);
        }

        idRules.validateDuplicates(context, fieldsById, findings);
        return findings;
    }
}
