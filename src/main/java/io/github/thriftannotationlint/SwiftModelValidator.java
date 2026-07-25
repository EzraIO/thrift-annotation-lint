package io.github.thriftannotationlint;

import javax.annotation.processing.ProcessingEnvironment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Coordinates normalized logical-field, union, and recursive-cycle validation. */
final class SwiftModelValidator {
    private final LogicalFieldResolver logicalFieldResolver;
    private final LogicalFieldValidator logicalFieldValidator;
    private final SwiftUnionValidator unionValidator;
    private final RecursiveModelCycleValidator cycleValidator;

    SwiftModelValidator(
            ProcessingEnvironment processingEnvironment,
            SwiftTypeInspector typeInspector) {
        this.logicalFieldResolver = new LogicalFieldResolver();
        this.logicalFieldValidator = new LogicalFieldValidator(typeInspector);
        this.unionValidator = new SwiftUnionValidator(processingEnvironment.getTypeUtils());
        this.cycleValidator = new RecursiveModelCycleValidator(typeInspector);
    }

    ValidationResult validate(SwiftModel model) {
        if (model.kind() == SwiftModel.Kind.ENUM) {
            return new ValidationResult(
                    model,
                    null,
                    Collections.<Finding>emptyList());
        }

        ResolvedLogicalFields resolvedFields =
                logicalFieldResolver.resolve(model.fieldParts());
        List<Finding> findings = logicalFieldValidator.validate(model, resolvedFields);
        if (model.kind() == SwiftModel.Kind.UNION) {
            unionValidator.validateDiscriminatorCollisions(model, resolvedFields, findings);
        }

        List<FieldPart> noLvtParts = noLvtFieldParts(model.fieldParts());
        if (noLvtParts != null) {
            ResolvedLogicalFields noLvtResolvedFields =
                    logicalFieldResolver.resolve(noLvtParts);
            List<Finding> noLvtFindings =
                    logicalFieldValidator.validate(model, noLvtResolvedFields);
            if (model.kind() == SwiftModel.Kind.UNION) {
                unionValidator.validateDiscriminatorCollisions(
                        model,
                        noLvtResolvedFields,
                        noLvtFindings);
            }
            addNoLvtFindings(findings, noLvtFindings);
        }

        if (model.kind() == SwiftModel.Kind.UNION) {
            unionValidator.validate(model, resolvedFields, findings);
        }
        return new ValidationResult(model, resolvedFields, findings);
    }

    private List<FieldPart> noLvtFieldParts(List<FieldPart> fieldParts) {
        List<FieldPart> variants = new ArrayList<FieldPart>();
        boolean changed = false;
        for (FieldPart part : fieldParts) {
            FieldPart variant = part.noLvtVariant();
            variants.add(variant);
            changed |= variant != part;
        }
        return changed ? variants : null;
    }

    private void addNoLvtFindings(
            List<Finding> findings,
            List<Finding> noLvtFindings) {
        Set<String> existingLocations = new LinkedHashSet<String>();
        for (Finding finding : findings) {
            existingLocations.add(findingLocationKey(finding));
        }
        for (Finding finding : noLvtFindings) {
            if (existingLocations.add(findingLocationKey(finding))) {
                findings.add(finding.relocated(
                        finding.element(),
                        "If emitted bytecode omits LocalVariableTable parameter names: "));
            }
        }
    }

    private String findingLocationKey(Finding finding) {
        String element = finding.element() == null
                ? ""
                : ElementNames.qualifiedMemberName(finding.element());
        return element + "\u0000" + finding.code().id();
    }

    List<Finding> validateCycles(List<ValidationResult> validations) {
        return cycleValidator.validate(validations);
    }

    /** Round-scoped immutable validation data reused by graph-wide rules. */
    static final class ValidationResult {
        private final SwiftModel model;
        private final ResolvedLogicalFields resolvedFields;
        private final List<Finding> findings;

        ValidationResult(
                SwiftModel model,
                ResolvedLogicalFields resolvedFields,
                List<Finding> findings) {
            this.model = model;
            this.resolvedFields = resolvedFields;
            this.findings = Collections.unmodifiableList(
                    new ArrayList<Finding>(findings));
        }

        SwiftModel model() {
            return model;
        }

        ResolvedLogicalFields resolvedFields() {
            return resolvedFields;
        }

        List<Finding> findings() {
            return findings;
        }
    }
}
