package io.github.thriftannotationlint.internal.planning;

import io.github.thriftannotationlint.internal.config.ProcessorMode;
import io.github.thriftannotationlint.internal.config.ProcessorOptions;
import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.DiagnosticReporter;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.extract.SwiftModelClassifier;
import io.github.thriftannotationlint.internal.extract.SwiftModelExtractor;
import io.github.thriftannotationlint.internal.extract.SwiftMemberResolver;
import io.github.thriftannotationlint.internal.model.FieldPart;
import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.types.ThriftTypeInspector;
import io.github.thriftannotationlint.internal.types.IncompleteTypeGate;
import io.github.thriftannotationlint.internal.validation.ModelValidation;
import io.github.thriftannotationlint.internal.validation.SwiftModelValidator;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time validator for Java models using supported Thrift codec annotations.
 *
 * <p>The processor intentionally references annotations by qualified name so the processor core
 * has no runtime dependency on Swift or Drift.</p>
 */
final class RoundValidationEngine {
    private ProcessingEnvironment processingEnv;
    private ProcessorOptions options;
    private ProcessorMode mode;
    private boolean optionErrorReported;
    private CompilationState state;
    private SwiftModelExtractor extractor;
    private SwiftModelValidator validator;
    private ThriftTypeInspector typeInspector;
    private DemandClosure demandClosure;
    private FindingRouter findingRouter;
    private RoundPlanner roundPlanner;
    private IncompleteTypeGate incompleteTypeGate;
    private ReferenceDemandScheduler referenceScheduler;

    RoundValidationEngine() {
    }

    synchronized void init(ProcessingEnvironment environment) {
        this.processingEnv = environment;
        this.optionErrorReported = false;
        this.options = ProcessorOptions.parse(environment.getOptions());
        this.mode = options.mode();
        this.state = new CompilationState(options.maxExactModels());
        this.findingRouter = new FindingRouter(state);
        this.typeInspector = new ThriftTypeInspector(
                environment.getTypeUtils(),
                environment.getElementUtils());
        this.incompleteTypeGate = new IncompleteTypeGate();
        SwiftModelClassifier modelClassifier = new SwiftModelClassifier();
        SwiftMemberResolver memberResolver = new SwiftMemberResolver(
                environment.getElementUtils(), environment.getTypeUtils());
        this.demandClosure = new DemandClosure(typeInspector, modelClassifier);
        this.referenceScheduler = new ReferenceDemandScheduler(
                demandClosure, typeInspector, state, findingRouter);
        this.roundPlanner = new RoundPlanner(
                environment,
                state,
                typeInspector,
                modelClassifier,
                demandClosure,
                memberResolver);
        this.extractor = new SwiftModelExtractor(
                environment, typeInspector, incompleteTypeGate, memberResolver);
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
        extractor.beginRound();
        RoundPlanner.Plan plan = roundPlanner.plan(roundEnvironment);
        incompleteTypeGate.beginRound();
        boolean rebuildDemandClosure = plan.rebuildDemandClosure();
        Map<String, ModelDemand> candidates = plan.currentCandidates();
        Element roundAnchor = plan.diagnosticAnchor();
        List<Finding> allFindings =
                new ArrayList<Finding>(plan.declarationFindings());
        DemandClosure.WorkQueue work = demandClosure.newQueue();
        for (ModelDemand candidate : plan.modelDemands()) {
            demandClosure.schedule(candidate, work);
        }
        referenceScheduler.scheduleContainerRoots(
                plan.containerDemands(),
                work,
                rebuildDemandClosure,
                incompleteTypeGate,
                allFindings);
        // Pending state is an aggregate for the next round. Resolved exact instances must not
        // erase an unresolved sibling with the same raw model name later in this work queue.
        state.beginPendingAggregation();
        for (int workIndex = 0; workIndex < work.size(); workIndex++) {
            ModelDemand candidate = work.get(workIndex);
            String typeName = candidate.type().getQualifiedName().toString();
            if (!state.beginModelValidation(
                    candidate.cacheKey(), candidate.forceRevalidation())) {
                continue;
            }
            try {
                SwiftModelExtractor.ExtractionResult extraction =
                        extractor.extract(
                                candidate.declaredType(),
                                candidate.kind(),
                                candidate.identity(),
                                candidate.cacheKey(),
                                candidate.dialect(),
                                state.compilationTypes());
                SwiftModel model = extraction.model();
                if (reclassifyCompletedContainerCandidate(
                        candidate,
                        work,
                        allFindings)) {
                    continue;
                }
                boolean unresolved = extraction.hasUnresolvedSymbols()
                        || incompleteTypeGate.containsErrorType(model);
                if (unresolved) {
                    state.markPending(typeName, candidate.kind(), candidate.dialect());
                    state.removeModel(model.cacheKey());
                    if (!state.isSourceModelIdentity(candidate.cacheKey())) {
                        state.releaseReferencedIdentity(candidate.cacheKey());
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
                ModelValidation validation = validator.validate(model);
                state.storeValidationResult(validation);
                findingRouter.addCandidateFindings(
                        candidate, validation.findings(), allFindings);
                referenceScheduler.scheduleModelReferences(
                        candidate, model, work, allFindings);
            }
            catch (RuntimeException failure) {
                Finding finding = Finding.error(
                        DiagnosticCode.INTERNAL_PROCESSOR_FAILURE,
                        candidate.type(),
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
        if (candidate.kind() != SwiftModel.Kind.STRUCT
                && candidate.kind() != SwiftModel.Kind.UNION) {
            return false;
        }
        TypeMirror containerType =
                typeInspector.containerClassificationType(candidate.declaredType());
        if (containerType == null) {
            return false;
        }

        String typeName = candidate.type().getQualifiedName().toString();
        ContainerDemand containerRoot = new ContainerDemand(
                candidate.type(), containerType, candidate.dialect());
        if (!state.migrateSourceModelToContainer(
                typeName, candidate.dialect(), candidate.cacheKey())) {
            state.releaseModelIdentity(candidate.cacheKey());
        }
        referenceScheduler.scheduleContainerRoots(
                Collections.singletonList(containerRoot),
                work,
                candidate.forceRevalidation(),
                incompleteTypeGate,
                findings);
        return true;
    }

    private void validateRequestedRuntimeType(
            ModelDemand candidate,
            SwiftModel model,
            List<Finding> findings) {
        if (candidate.diagnosticAnchor() == null
                || model.builder() == null
                || model.builder().getTypeParameters().isEmpty()
                || isRuntimeParameterizedType(candidate.requestedType())) {
            return;
        }
        findingRouter.addCandidateFinding(candidate, Finding.error(
                        DiagnosticCode.INVALID_BUILDER,
                        model.builder(),
                        "Generic builder '" + model.builder().getQualifiedName()
                                + "' cannot build runtime type '" + candidate.requestedType()
                                + "' because " + model.dialect().runtimeName()
                                + " receives a TypeVariable, wildcard, or raw "
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
        ExactModelBudget.Reservation reservation = state.reserveResolvedExactModel(
                candidate.cacheKey());
        if (reservation.accepted()) {
            return true;
        }
        if (reservation == ExactModelBudget.Reservation.EXCEEDED_FIRST) {
            Element anchor = candidate.diagnosticAnchor() == null
                    ? candidate.type()
                    : candidate.diagnosticAnchor();
            findings.add(Finding.error(
                    DiagnosticCode.VALIDATION_LIMIT_EXCEEDED,
                    anchor,
                    "Reachable exact-model validation exceeded the configured limit of "
                            + state.exactModelLimit() + " instances (-A"
                            + ProcessorOptions.MAX_EXACT_MODELS_OPTION
                            + "). Increase the limit for a known finite graph or fix "
                            + "non-converging generic recursion."));
        }
        return false;
    }

}
