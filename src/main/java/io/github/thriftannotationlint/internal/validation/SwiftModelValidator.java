package io.github.thriftannotationlint.internal.validation;

import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.extract.LogicalFieldResolver;
import io.github.thriftannotationlint.internal.model.FieldPart;
import io.github.thriftannotationlint.internal.model.ResolvedLogicalFields;
import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.model.ElementNames;
import io.github.thriftannotationlint.internal.types.SwiftTypeInspector;

import javax.annotation.processing.ProcessingEnvironment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Coordinates normalized logical-field, union, and recursive-cycle validation. */
public final class SwiftModelValidator {
    private final LogicalFieldResolver logicalFieldResolver;
    private final LogicalFieldValidator logicalFieldValidator;
    private final SwiftUnionValidator unionValidator;
    private final RecursiveModelCycleValidator cycleValidator;

    public SwiftModelValidator(
            ProcessingEnvironment processingEnvironment,
            SwiftTypeInspector typeInspector) {
        this.logicalFieldResolver = new LogicalFieldResolver();
        this.logicalFieldValidator = new LogicalFieldValidator(typeInspector);
        this.unionValidator = new SwiftUnionValidator(processingEnvironment.getTypeUtils());
        this.cycleValidator = new RecursiveModelCycleValidator(typeInspector);
    }

    public ModelValidation validate(SwiftModel model) {
        if (model.kind() == SwiftModel.Kind.ENUM) {
            return new ModelValidation(
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
        return new ModelValidation(model, resolvedFields, findings);
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

    public List<Finding> validateCycles(List<ModelValidation> validations) {
        return cycleValidator.validate(validations);
    }
}
