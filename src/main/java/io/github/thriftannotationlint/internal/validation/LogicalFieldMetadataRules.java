package io.github.thriftannotationlint.internal.validation;

import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.model.FieldPart;
import io.github.thriftannotationlint.internal.model.ResolvedLogicalFields;
import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.model.ThriftAnnotations;
import io.github.thriftannotationlint.internal.model.ThriftFieldData;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates field annotation metadata without inspecting Java codec shapes. */
final class LogicalFieldMetadataRules {
    void validate(
            LogicalFieldValidationContext context,
            ResolvedLogicalFields.LogicalField field,
            List<Finding> findings) {
        validateFieldAnnotationMaps(context.model(), field, findings);
        validateFieldNames(context.model(), field, findings);
        validateRequiredness(context.model(), field, findings);
        validateLegacyId(context.model(), field, context.idResolution(), findings);
        validateRecursiveField(context.model(), field, findings);
    }

    private void validateFieldAnnotationMaps(
            SwiftModel model,
            ResolvedLogicalFields.LogicalField field,
            List<Finding> findings) {
        Set<Map<String, String>> nonEmptyMaps = new LinkedHashSet<Map<String, String>>();
        for (FieldPart part : field.parts()) {
            ThriftAnnotations.IdlAnnotations idl = part.thriftField().idlAnnotations();
            if (!idl.duplicateKeys().isEmpty()) {
                findings.add(Finding.error(
                        DiagnosticCode.CONFLICTING_IDL_ANNOTATIONS,
                        part.element(),
                        part.thriftField().annotation(),
                        idl.sourceValue(),
                        ValidationText.modelField(model.displayName(), field.displayName())
                                + " declares duplicate IDL annotation keys "
                                + idl.duplicateKeys() + "."));
            }
            if (!idl.values().isEmpty()) {
                nonEmptyMaps.add(idl.values());
            }
        }
        if (nonEmptyMaps.size() > 1) {
            FieldPart target = field.firstPartWithIdlAnnotations();
            findings.add(Finding.error(
                    DiagnosticCode.CONFLICTING_IDL_ANNOTATIONS,
                    target.element(),
                    target.thriftField().annotation(),
                    target.thriftField().idlAnnotations().sourceValue(),
                    ValidationText.modelField(model.displayName(), field.displayName())
                            + " declares conflicting IDL annotation maps."));
        }
    }

    private void validateFieldNames(
            SwiftModel model,
            ResolvedLogicalFields.LogicalField field,
            List<Finding> findings) {
        Set<String> names = new LinkedHashSet<String>();
        for (FieldPart part : field.parts()) {
            if (part.thriftField().explicitName() != null) {
                names.add(part.thriftField().explicitName());
            }
        }
        if (names.size() > 1) {
            findings.add(Finding.error(
                    DiagnosticCode.CONFLICTING_FIELD_NAME,
                    field.lastPart().element(),
                    ValidationText.modelField(model.displayName(), field.displayName())
                            + " declares multiple explicit names " + names + "."));
        }
    }

    private void validateRequiredness(
            SwiftModel model,
            ResolvedLogicalFields.LogicalField field,
            List<Finding> findings) {
        Set<String> values = field.explicitRequirednessValues();
        if (values.size() > 1) {
            FieldPart target = field.firstPartWithExplicitRequiredness();
            findings.add(Finding.error(
                    DiagnosticCode.CONFLICTING_REQUIREDNESS,
                    target.element(),
                    ValidationText.modelField(model.displayName(), field.displayName())
                            + " declares conflicting requiredness values "
                            + values + "."));
        }
        if (model.kind() == SwiftModel.Kind.UNION
                && (values.contains("REQUIRED") || values.contains("OPTIONAL"))) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_UNION_REQUIREDNESS,
                    field.lastPart().element(),
                    ValidationText.unionField(model.displayName(), field.displayName())
                            + " must not be marked required or optional."));
        }
    }

    private void validateLegacyId(
            SwiftModel model,
            ResolvedLogicalFields.LogicalField field,
            ResolvedLogicalFields.IdResolution idResolution,
            List<Finding> findings) {
        Set<Short> ids = field.ids(idResolution);
        if (ids.size() != 1) {
            return;
        }
        short id = ids.iterator().next();
        Set<Boolean> legacyValues = new LinkedHashSet<Boolean>();
        for (FieldPart part : field.parts()) {
            ThriftFieldData data = part.thriftField();
            // Swift treats isLegacyId=false as absent when the part has no configured ID.
            if (data.id() != null || data.legacyId()) {
                legacyValues.add(Boolean.valueOf(data.legacyId()));
            }
        }
        if (legacyValues.size() > 1) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_LEGACY_ID,
                    field.lastPart().element(),
                    ValidationText.modelField(model.displayName(), field.displayName())
                            + " mixes isLegacyId=true and isLegacyId=false."));
        }
        if (id < 0 && !legacyValues.contains(Boolean.TRUE)) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_LEGACY_ID,
                    field.lastPartWithId().element(),
                    ValidationText.modelField(model.displayName(), field.displayName())
                            + " has a negative ID and must set isLegacyId=true."));
        }
        else if (id >= 0 && legacyValues.contains(Boolean.TRUE)) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_LEGACY_ID,
                    field.lastPartWithId().element(),
                    ValidationText.modelField(model.displayName(), field.displayName())
                            + " sets isLegacyId=true for a non-negative ID."));
        }
    }

    private void validateRecursiveField(
            SwiftModel model,
            ResolvedLogicalFields.LogicalField field,
            List<Finding> findings) {
        Set<Boolean> recursiveValues = new LinkedHashSet<Boolean>();
        for (FieldPart part : field.parts()) {
            if (part.thriftField().recursive() != null) {
                recursiveValues.add(part.thriftField().recursive());
            }
        }
        if (recursiveValues.size() > 1) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_RECURSIVE_FIELD,
                    field.lastPart().element(),
                    ValidationText.modelField(model.displayName(), field.displayName())
                            + " declares conflicting recursive-reference settings."));
        }
        if (model.kind() == SwiftModel.Kind.STRUCT
                && recursiveValues.contains(Boolean.TRUE)
                && !field.explicitRequirednessValues().contains("OPTIONAL")) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_RECURSIVE_FIELD,
                    field.lastPart().element(),
                    "Recursive field '" + field.displayName() + "' in Thrift struct '"
                            + model.displayName() + "' must be marked optional."));
        }
    }
}
