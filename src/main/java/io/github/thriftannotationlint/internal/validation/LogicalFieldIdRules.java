package io.github.thriftannotationlint.internal.validation;

import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.model.FieldPart;
import io.github.thriftannotationlint.internal.model.ResolvedLogicalFields;
import io.github.thriftannotationlint.internal.model.SwiftModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates resolved field IDs and cross-field uniqueness. */
final class LogicalFieldIdRules {
    private static final int FIRST_CONFLICTING_DECLARATION_INDEX = 1;

    void validateField(
            LogicalFieldValidationContext context,
            ResolvedLogicalFields.LogicalField field,
            Map<Short, List<ResolvedLogicalFields.LogicalField>> fieldsById,
            List<Finding> findings) {
        ResolvedLogicalFields.IdResolution idResolution = context.idResolution();
        Set<Short> ids = field.ids(idResolution);
        if (ids.size() > 1) {
            addConflictingId(context.model(), field, ids, findings);
            return;
        }
        if (field.hasUnresolvedPart(idResolution)) {
            addMissingId(context.model(), field, idResolution, findings);
            return;
        }
        if (!field.hasUnreliableIdentity()) {
            registerField(fieldsById, ids.iterator().next(), field);
        }
    }

    void validateDuplicates(
            LogicalFieldValidationContext context,
            Map<Short, List<ResolvedLogicalFields.LogicalField>> fieldsById,
            List<Finding> findings) {
        SwiftModel model = context.model();
        for (Map.Entry<Short, List<ResolvedLogicalFields.LogicalField>> entry
                : fieldsById.entrySet()) {
            List<ResolvedLogicalFields.LogicalField> fields = entry.getValue();
            if (fields.size() <= 1) {
                continue;
            }
            List<String> names = new ArrayList<String>();
            for (ResolvedLogicalFields.LogicalField field : fields) {
                names.add(field.displayName());
            }
            ResolvedLogicalFields.LogicalField targetField =
                    fields.get(FIRST_CONFLICTING_DECLARATION_INDEX);
            FieldPart target = targetField.lastPartWithId();
            findings.add(Finding.error(
                    DiagnosticCode.DUPLICATE_FIELD_ID,
                    target.element(),
                    target.thriftField().annotation(),
                    target.thriftField().idSource(),
                    ValidationText.model(model.displayName())
                            + " uses field ID "
                            + entry.getKey() + " for different logical fields " + names + "."));
        }
    }

    private void addConflictingId(
            SwiftModel model,
            ResolvedLogicalFields.LogicalField field,
            Set<Short> ids,
            List<Finding> findings) {
        FieldPart target = field.lastPartWithId();
        findings.add(Finding.error(
                DiagnosticCode.CONFLICTING_FIELD_ID,
                target.element(),
                target.thriftField().annotation(),
                target.thriftField().idSource(),
                ValidationText.modelField(model.displayName(), field.displayName())
                        + " declares conflicting IDs " + ids + "."));
    }

    private void addMissingId(
            SwiftModel model,
            ResolvedLogicalFields.LogicalField field,
            ResolvedLogicalFields.IdResolution idResolution,
            List<Finding> findings) {
        FieldPart target = field.firstUnresolvedPart(idResolution);
        if (target.isLogicalNameReliable()) {
            findings.add(Finding.error(
                    DiagnosticCode.MISSING_FIELD_ID,
                    target.element(),
                    ValidationText.modelField(model.displayName(), field.displayName())
                            + " does not resolve a field ID after "
                            + "Swift's two-phase name inference."));
        }
    }

    private void registerField(
            Map<Short, List<ResolvedLogicalFields.LogicalField>> fieldsById,
            Short id,
            ResolvedLogicalFields.LogicalField field) {
        List<ResolvedLogicalFields.LogicalField> sameId = fieldsById.get(id);
        if (sameId == null) {
            sameId = new ArrayList<ResolvedLogicalFields.LogicalField>();
            fieldsById.put(id, sameId);
        }
        sameId.add(field);
    }
}
