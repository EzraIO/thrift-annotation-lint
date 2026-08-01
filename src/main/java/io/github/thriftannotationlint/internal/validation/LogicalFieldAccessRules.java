package io.github.thriftannotationlint.internal.validation;

import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.model.ElementNames;
import io.github.thriftannotationlint.internal.model.FieldPart;
import io.github.thriftannotationlint.internal.model.ResolvedLogicalFields;
import io.github.thriftannotationlint.internal.model.SwiftModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Validates readable and writable runtime access paths. */
final class LogicalFieldAccessRules {
    private static final int FIRST_CONFLICTING_DECLARATION_INDEX = 1;

    void validateAccessPaths(
            LogicalFieldValidationContext context,
            ResolvedLogicalFields.LogicalField field,
            List<Finding> findings) {
        boolean readable = false;
        boolean writable = false;
        for (FieldPart part : field.parts()) {
            readable |= part.isReadable();
            writable |= part.isWritable();
        }
        if (!readable || !writable) {
            String missing = !readable && !writable
                    ? "read and write"
                    : (!readable ? "read" : "write");
            findings.add(Finding.error(
                    DiagnosticCode.MISSING_ACCESS_PATH,
                    field.firstPart().element(),
                    ValidationText.modelField(
                            context.model().displayName(), field.displayName())
                            + " does not have a valid " + missing + " path."));
        }
    }

    void validateRuntimeSelection(
            LogicalFieldValidationContext context,
            ResolvedLogicalFields.LogicalField field,
            List<Finding> findings) {
        SwiftModel model = context.model();
        List<FieldPart> fields = new ArrayList<FieldPart>();
        List<FieldPart> getters = new ArrayList<FieldPart>();
        collectExtractors(field, fields, getters);
        validateWinningExtractors(model, field, fields, getters, findings);
        if (model.kind() == SwiftModel.Kind.UNION) {
            validateUnionInjections(model, field, findings);
        }
    }

    private void collectExtractors(
            ResolvedLogicalFields.LogicalField field,
            List<FieldPart> fields,
            List<FieldPart> getters) {
        for (FieldPart part : field.parts()) {
            if (!part.isReadable()) {
                continue;
            }
            if (part.source() == FieldPart.Source.GETTER) {
                getters.add(part);
            }
            else if (part.source() == FieldPart.Source.FIELD) {
                fields.add(part);
            }
        }
    }

    private void validateWinningExtractors(
            SwiftModel model,
            ResolvedLogicalFields.LogicalField field,
            List<FieldPart> fields,
            List<FieldPart> getters,
            List<Finding> findings) {
        // Swift installs field extractors before method extractors, so getters win deterministically.
        List<FieldPart> winningExtractors = getters.isEmpty() ? fields : getters;
        if (winningExtractors.size() > 1) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_METHOD_OR_CONSTRUCTOR,
                    winningExtractors.get(FIRST_CONFLICTING_DECLARATION_INDEX).declaration(),
                    ValidationText.modelField(model.displayName(), field.displayName())
                            + " declares multiple "
                            + winningExtractors.get(0).source().name().toLowerCase(Locale.ROOT)
                            + " extraction paths; Swift retains only one, selected by "
                            + "unspecified reflection order."));
        }
    }

    private void validateUnionInjections(
            SwiftModel model,
            ResolvedLogicalFields.LogicalField field,
            List<Finding> findings) {
        Map<String, FieldPart> methodInjections = new LinkedHashMap<String, FieldPart>();
        for (FieldPart part : field.parts()) {
            if (!isMethodInjection(part)) {
                continue;
            }
            String declaration = ElementNames.qualifiedMemberName(part.declaration());
            if (!methodInjections.containsKey(declaration)) {
                methodInjections.put(declaration, part);
            }
        }
        if (methodInjections.size() > 1) {
            List<FieldPart> injections = new ArrayList<FieldPart>(methodInjections.values());
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_METHOD_OR_CONSTRUCTOR,
                    injections.get(FIRST_CONFLICTING_DECLARATION_INDEX).declaration(),
                    ValidationText.unionField(model.displayName(), field.displayName())
                            + " declares multiple method injection paths; "
                            + "Swift retains only one, selected by unspecified reflection order."));
        }
    }

    private boolean isMethodInjection(FieldPart part) {
        return part.isWritable()
                && (part.source() == FieldPart.Source.SETTER
                || part.source() == FieldPart.Source.METHOD_PARAMETER);
    }
}
