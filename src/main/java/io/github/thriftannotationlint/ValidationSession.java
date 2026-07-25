package io.github.thriftannotationlint;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time validator for Java models using Facebook Swift codec annotations.
 *
 * <p>The processor intentionally references annotations by qualified name so the processor core
 * has no runtime dependency on Swift.</p>
 */
final class ValidationSession {
    private ProcessingEnvironment processingEnv;
    private ProcessorOptions options;
    private ProcessorMode mode;
    private boolean optionErrorReported;
    private CompilationState state;
    private SwiftModelExtractor extractor;
    private SwiftModelValidator validator;
    private SwiftTypeInspector typeInspector;
    private DemandClosure demandClosure;
    private FindingRouter findingRouter;
    private RoundPlanner roundPlanner;

    synchronized void init(ProcessingEnvironment environment) {
        this.processingEnv = environment;
        this.optionErrorReported = false;
        this.options = ProcessorOptions.parse(environment.getOptions());
        this.mode = options.mode();
        this.state = new CompilationState(options.maxExactModels());
        this.findingRouter = new FindingRouter(state);
        this.typeInspector = new SwiftTypeInspector(
                environment.getTypeUtils(),
                environment.getElementUtils());
        SwiftModelClassifier modelClassifier = new SwiftModelClassifier();
        this.demandClosure = new DemandClosure(typeInspector, modelClassifier);
        this.roundPlanner = new RoundPlanner(
                environment,
                state,
                typeInspector,
                modelClassifier,
                demandClosure);
        this.extractor = new SwiftModelExtractor(environment, typeInspector);
        this.validator = new SwiftModelValidator(environment, typeInspector);
    }

    boolean process(
            Set<? extends TypeElement> annotations,
            RoundEnvironment roundEnvironment) {
        if (roundEnvironment.processingOver()) {
            return false;
        }
        if (options.validationError() != null) {
            if (!optionErrorReported) {
                optionErrorReported = true;
                Element sourceElement = roundPlanner.firstRootElement(roundEnvironment);
                new DiagnosticReporter(processingEnv.getMessager(), ProcessorMode.STRICT).report(
                        Finding.error(
                                DiagnosticCode.INVALID_PROCESSOR_OPTION,
                                sourceElement,
                                options.validationError()));
            }
            return false;
        }
        if (!roundPlanner.isRelevant(annotations, roundEnvironment)) {
            return false;
        }
        RoundPlanner.Plan plan = roundPlanner.plan(roundEnvironment);
        boolean rebuildDemandClosure = plan.rebuildDemandClosure();
        Map<String, ModelDemand> candidates = plan.currentCandidates();
        Element roundAnchor = plan.diagnosticAnchor();
        List<Finding> allFindings =
                new ArrayList<Finding>(plan.declarationFindings());
        DemandClosure.WorkQueue work = demandClosure.newQueue();
        for (ModelDemand candidate : plan.modelDemands()) {
            demandClosure.schedule(candidate, work);
        }
        for (ContainerDemand containerRoot : plan.containerDemands()) {
            if (containsErrorType(
                    containerRoot.element.asType(), new LinkedHashSet<String>())) {
                // A visible Iterable or Set can still become a higher-priority Map when a
                // generated superclass completes. Diagnostics and exact-model reservations are
                // irreversible, so validate this root only after its hierarchy is stable.
                continue;
            }
            validateAnnotatedContainerRoot(containerRoot, allFindings);
            addContainerRootReferences(
                    containerRoot,
                    work,
                    rebuildDemandClosure);
        }
        // Pending state is an aggregate for the next round. Resolved exact instances must not
        // erase an unresolved sibling with the same raw model name later in this work queue.
        state.beginPendingAggregation();
        for (int workIndex = 0; workIndex < work.size(); workIndex++) {
            ModelDemand candidate = work.get(workIndex);
            String typeName = candidate.type.getQualifiedName().toString();
            String typeIdentity = candidate.identity;
            if (!state.beginModelValidation(typeIdentity, candidate.forceRevalidation)) {
                continue;
            }
            try {
                SwiftModelExtractor.ExtractionResult extraction =
                        extractor.extract(
                                candidate.declaredType,
                                candidate.kind,
                                candidate.identity,
                                state.compilationTypes());
                SwiftModel model = extraction.model();
                if (reclassifyCompletedContainerCandidate(
                        candidate,
                        work,
                        allFindings)) {
                    continue;
                }
                boolean unresolved = extraction.hasUnresolvedSymbols()
                        || containsErrorType(model);
                if (unresolved) {
                    state.markPending(typeName, candidate.kind);
                    state.removeModel(model.identity());
                    if (!state.isSourceModelIdentity(typeIdentity)) {
                        state.releaseReferencedIdentity(typeIdentity);
                    }
                    // Messager errors cannot be withdrawn. Findings derived from an incomplete
                    // symbol graph are therefore buffered by re-extracting the model next round.
                    continue;
                }
                if (!reserveResolvedExactModel(candidate, allFindings)) {
                    continue;
                }
                state.storeResolvedModel(model);
                findingRouter.addCandidateFindings(
                        candidate, extraction.findings(), allFindings);
                validateRequestedRuntimeType(candidate, model, allFindings);
                SwiftModelValidator.ValidationResult validation = validator.validate(model);
                state.storeValidationResult(validation);
                findingRouter.addCandidateFindings(
                        candidate, validation.findings(), allFindings);
                addReferencedModels(
                        candidate,
                        model,
                        work,
                        allFindings);
            }
            catch (RuntimeException failure) {
                Finding finding = Finding.error(
                        DiagnosticCode.INTERNAL_PROCESSOR_FAILURE,
                        candidate.type,
                        "ThriftAnnotationLint failed while validating '" + typeName + "' ("
                                + failure.getClass().getSimpleName() + "). Report AW9002 with "
                                + "the model name, ThriftAnnotationLint version, compiler, and JDK versions.");
                findingRouter.addCandidateFinding(candidate, finding, allFindings);
            }
        }

        List<Finding> cycleFindings;
        try {
            cycleFindings = validator.validateCycles(
                    state.validationResults());
        }
        catch (RuntimeException failure) {
            cycleFindings = Collections.emptyList();
            allFindings.add(Finding.error(
                    DiagnosticCode.INTERNAL_PROCESSOR_FAILURE,
                    roundAnchor,
                    "ThriftAnnotationLint failed while validating the reachable model graph ("
                            + failure.getClass().getSimpleName() + "). Report AW9002 with "
                            + "the ThriftAnnotationLint, compiler, and JDK versions."));
        }
        findingRouter.addCycleFindings(
                cycleFindings,
                candidates,
                roundPlanner.firstRootElement(roundEnvironment),
                allFindings);
        findingRouter.reportAll(processingEnv.getMessager(), mode, allFindings);
        // ThriftAnnotationLint validates but does not own Swift annotations; other processors may inspect them.
        return false;
    }

    private boolean reclassifyCompletedContainerCandidate(
            ModelDemand candidate,
            DemandClosure.WorkQueue work,
            List<Finding> findings) {
        if (candidate.kind != SwiftModel.Kind.STRUCT
                && candidate.kind != SwiftModel.Kind.UNION) {
            return false;
        }
        TypeMirror containerType =
                typeInspector.containerClassificationType(candidate.declaredType);
        if (containerType == null) {
            return false;
        }

        String typeName = candidate.type.getQualifiedName().toString();
        ContainerDemand containerRoot = new ContainerDemand(candidate.type, containerType);
        if (!state.migrateSourceModelToContainer(typeName, candidate.identity)) {
            state.releaseModelIdentity(candidate.identity);
        }
        validateAnnotatedContainerRoot(containerRoot, findings);
        addContainerRootReferences(
                containerRoot,
                work,
                candidate.forceRevalidation);
        return true;
    }

    private void addReferencedModels(
            ModelDemand owner,
            SwiftModel model,
            DemandClosure.WorkQueue work,
            List<Finding> findings) {
        for (FieldPart part : model.fieldParts()) {
            List<ModelReference> references = demandClosure.references(part.javaType());
            for (ModelReference modelReference : references) {
                DemandClosure.Expansion expansion = demandClosure.expandAndSchedule(
                        owner, part, modelReference, work);
                if (expansion.finding() != null) {
                    findingRouter.addCandidateFinding(
                            owner, expansion.finding(), findings);
                    continue;
                }
                ModelDemand demand = expansion.demand();
                if (demand == null) {
                    continue;
                }
                String referencedName = demand.type.getQualifiedName().toString();
                if (!state.isSourceModelName(referencedName)) {
                    state.putDependencyAnchorIfAbsent(
                            demand.identity, demand.diagnosticAnchor);
                }
            }
        }
    }

    private void validateAnnotatedContainerRoot(
            ContainerDemand containerRoot,
            List<Finding> findings) {
        TypeMirror type = containerRoot.classificationType;
        Finding finding = null;
        if (!typeInspector.isSupported(type)) {
            finding = Finding.error(
                    DiagnosticCode.UNSUPPORTED_JAVA_TYPE,
                    containerRoot.element,
                    "Annotated type '" + containerRoot.element.getQualifiedName()
                            + "' is classified by Swift as a container, but its resolved type '"
                            + type + "' contains an unsupported element or key/value type.");
        }
        if (finding != null) {
            findingRouter.add(finding, findings);
        }
    }

    private void addContainerRootReferences(
            ContainerDemand containerRoot,
            DemandClosure.WorkQueue work,
            boolean forceRevalidation) {
        List<ModelReference> references =
                demandClosure.references(containerRoot.classificationType);
        for (ModelReference modelReference : references) {
            ModelDemand demand = demandClosure.scheduleRootReference(
                    modelReference,
                    containerRoot.element,
                    forceRevalidation,
                    work);
            if (demand == null) {
                continue;
            }
            String referencedName = demand.type.getQualifiedName().toString();
            if (!state.isSourceModelName(referencedName)) {
                state.putDependencyAnchorIfAbsent(
                        demand.identity, containerRoot.element);
            }
        }
    }

    private void validateRequestedRuntimeType(
            ModelDemand candidate,
            SwiftModel model,
            List<Finding> findings) {
        if (candidate.diagnosticAnchor == null
                || model.builder() == null
                || model.builder().getTypeParameters().isEmpty()
                || isRuntimeParameterizedType(candidate.requestedType)) {
            return;
        }
        findingRouter.addCandidateFinding(candidate, Finding.error(
                        DiagnosticCode.INVALID_BUILDER,
                        model.builder(),
                        "Generic builder '" + model.builder().getQualifiedName()
                                + "' cannot build runtime type '" + candidate.requestedType
                                + "' because Swift receives a TypeVariable, wildcard, or raw "
                                + "Class instead of a ParameterizedType."),
                findings);
    }

    private boolean isRuntimeParameterizedType(TypeMirror type) {
        return type != null
                && type.getKind() == TypeKind.DECLARED
                && !((DeclaredType) type).getTypeArguments().isEmpty();
    }

    /**
     * Charges the exact-model budget only after extraction has proved that the candidate is a
     * complete model. Scheduling is too early: an ERROR type or a generated supertype can later
     * resolve to a container, and javac diagnostics emitted for that transient reservation cannot
     * be withdrawn.
     */
    private boolean reserveResolvedExactModel(
            ModelDemand candidate,
            List<Finding> findings) {
        String identity = candidate.identity;
        ExactModelBudget.Reservation reservation = state.reserveResolvedExactModel(identity);
        if (reservation.accepted()) {
            return true;
        }
        if (reservation == ExactModelBudget.Reservation.EXCEEDED_FIRST) {
            Element anchor = candidate.diagnosticAnchor == null
                    ? candidate.type
                    : candidate.diagnosticAnchor;
            findings.add(Finding.error(
                    DiagnosticCode.VALIDATION_LIMIT_EXCEEDED,
                    anchor,
                    "Reachable exact-model validation exceeded the configured limit of "
                            + state.exactModelLimit() + " instances (-A"
                            + ThriftAnnotationLintProcessor.MAX_EXACT_MODELS_OPTION
                            + "). Increase the limit for a known finite graph or fix "
                            + "non-converging generic recursion."));
        }
        return false;
    }

    private boolean containsErrorType(SwiftModel model) {
        for (FieldPart part : model.fieldParts()) {
            if (containsErrorType(part.javaType(), new LinkedHashSet<String>())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsErrorType(TypeMirror type, Set<String> visiting) {
        if (type == null) {
            return false;
        }
        if (type.getKind() == TypeKind.ERROR) {
            return true;
        }
        String key;
        try {
            key = type.getKind() + ":" + type;
        }
        catch (RuntimeException incompleteSymbol) {
            return true;
        }
        if (!visiting.add(key)) {
            return false;
        }
        try {
            if (type.getKind() == TypeKind.DECLARED) {
                DeclaredType declared = (DeclaredType) type;
                if (containsErrorType(declared.getEnclosingType(), visiting)) {
                    return true;
                }
                for (TypeMirror argument : declared.getTypeArguments()) {
                    if (containsErrorType(argument, visiting)) {
                        return true;
                    }
                }
                Element element = declared.asElement();
                if (element instanceof TypeElement) {
                    TypeElement declaredElement = (TypeElement) element;
                    if (containsErrorType(declaredElement.getSuperclass(), visiting)) {
                        return true;
                    }
                    for (TypeMirror interfaceType : declaredElement.getInterfaces()) {
                        if (containsErrorType(interfaceType, visiting)) {
                            return true;
                        }
                    }
                }
            }
            else if (type.getKind() == TypeKind.TYPEVAR) {
                return containsErrorType(((TypeVariable) type).getUpperBound(), visiting);
            }
            else if (type.getKind() == TypeKind.WILDCARD) {
                WildcardType wildcard = (WildcardType) type;
                return containsErrorType(wildcard.getExtendsBound(), visiting)
                        || containsErrorType(wildcard.getSuperBound(), visiting);
            }
            else if (type.getKind() == TypeKind.INTERSECTION) {
                for (TypeMirror bound : ((IntersectionType) type).getBounds()) {
                    if (containsErrorType(bound, visiting)) {
                        return true;
                    }
                }
            }
            return false;
        }
        catch (RuntimeException incompleteSymbol) {
            return true;
        }
        finally {
            visiting.remove(key);
        }
    }

}
