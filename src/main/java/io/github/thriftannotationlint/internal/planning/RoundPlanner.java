package io.github.thriftannotationlint.internal.planning;

import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.extract.SwiftMemberResolver;
import io.github.thriftannotationlint.internal.extract.SwiftModelClassifier;
import io.github.thriftannotationlint.internal.model.ElementNames;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;
import io.github.thriftannotationlint.internal.model.ThriftAnnotations;
import io.github.thriftannotationlint.internal.types.ThriftTypeInspector;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Discovers and deterministically orders all work for one annotation-processing round. */
public final class RoundPlanner {
    public static final class Plan {
        private final Map<String, ModelDemand> currentCandidates;
        private final List<ModelDemand> modelDemands;
        private final List<ContainerDemand> containerDemands;
        private final List<Finding> declarationFindings;
        private final Element diagnosticAnchor;
        private final boolean rebuildDemandClosure;

        private Plan(
                Map<String, ModelDemand> currentCandidates,
                List<ModelDemand> modelDemands,
                List<ContainerDemand> containerDemands,
                List<Finding> declarationFindings,
                Element diagnosticAnchor,
                boolean rebuildDemandClosure) {
            this.currentCandidates = Collections.unmodifiableMap(
                    new LinkedHashMap<String, ModelDemand>(currentCandidates));
            this.modelDemands = Collections.unmodifiableList(
                    new ArrayList<ModelDemand>(modelDemands));
            this.containerDemands = Collections.unmodifiableList(
                    new ArrayList<ContainerDemand>(containerDemands));
            this.declarationFindings = Collections.unmodifiableList(
                    new ArrayList<Finding>(declarationFindings));
            this.diagnosticAnchor = diagnosticAnchor;
            this.rebuildDemandClosure = rebuildDemandClosure;
        }

        public Map<String, ModelDemand> currentCandidates() {
            return currentCandidates;
        }

        public List<ModelDemand> modelDemands() {
            return modelDemands;
        }

        public List<ContainerDemand> containerDemands() {
            return containerDemands;
        }

        public List<Finding> declarationFindings() {
            return declarationFindings;
        }

        public Element diagnosticAnchor() {
            return diagnosticAnchor;
        }

        public boolean rebuildDemandClosure() {
            return rebuildDemandClosure;
        }
    }

    private final ProcessingEnvironment processingEnvironment;
    private final CompilationState state;
    private final ThriftTypeInspector typeInspector;
    private final DemandClosure demandClosure;
    private final SwiftMemberResolver memberResolver;
    private final RoundCandidateCollector candidateCollector;
    private final RoundDemandOrdering demandOrdering = new RoundDemandOrdering();

    public RoundPlanner(
            ProcessingEnvironment processingEnvironment,
            CompilationState state,
            ThriftTypeInspector typeInspector,
            SwiftModelClassifier modelClassifier,
            DemandClosure demandClosure) {
        this(
                processingEnvironment,
                state,
                typeInspector,
                modelClassifier,
                demandClosure,
                new SwiftMemberResolver(
                        processingEnvironment.getElementUtils(),
                        processingEnvironment.getTypeUtils()));
    }

    public RoundPlanner(
            ProcessingEnvironment processingEnvironment,
            CompilationState state,
            ThriftTypeInspector typeInspector,
            SwiftModelClassifier modelClassifier,
            DemandClosure demandClosure,
            SwiftMemberResolver memberResolver) {
        this.processingEnvironment = processingEnvironment;
        this.state = state;
        this.typeInspector = typeInspector;
        this.demandClosure = demandClosure;
        this.memberResolver = memberResolver;
        this.candidateCollector = new RoundCandidateCollector(
                processingEnvironment,
                state,
                typeInspector,
                modelClassifier,
                demandClosure,
                memberResolver);
    }

    public boolean isRelevant(
            Set<? extends TypeElement> annotations,
            RoundEnvironment roundEnvironment) {
        if (!thriftAnnotationsAvailable()) {
            return false;
        }
        return containsEnumRoot(roundEnvironment)
                || containsThriftAnnotation(annotations)
                || state.hasSourceRoots()
                || state.hasPendingModels();
    }

    public Plan plan(RoundEnvironment roundEnvironment) {
        memberResolver.beginRound();
        typeInspector.beginRound();
        CompilationState.RoundStart roundStart = state.beginActiveRound();
        candidateCollector.collectCompilationTypes(roundEnvironment.getRootElements());

        List<Finding> declarationFindings = new ArrayList<Finding>();
        RoundCandidateCollector.Result candidates = candidateCollector.collect(
                roundEnvironment,
                roundStart.previousPendingModels(),
                roundStart.rebuildDemandClosure(),
                declarationFindings);
        List<ModelDemand> orderedModels = demandOrdering.models(candidates.models());
        List<ContainerDemand> orderedContainers = demandOrdering.containers(
                candidates.containers(), candidates.currentContainerNames());
        registerCurrentModels(orderedModels);

        Element roundAnchor = firstRootElement(roundEnvironment);
        if (roundStart.rebuildDemandClosure()) {
            candidateCollector.appendHistoricalModelRoots(
                    candidates.models(), orderedModels, roundAnchor);
        }
        return new Plan(
                candidates.models(),
                orderedModels,
                orderedContainers,
                declarationFindings,
                roundAnchor,
                roundStart.rebuildDemandClosure());
    }

    private void registerCurrentModels(List<ModelDemand> orderedModels) {
        for (ModelDemand candidate : orderedModels) {
            state.registerSourceModel(
                    candidate.type.getQualifiedName().toString(),
                    candidate.kind,
                    candidate.dialect,
                    candidate.cacheKey());
        }
    }

    public Element firstRootElement(RoundEnvironment roundEnvironment) {
        Element first = null;
        for (Element element : roundEnvironment.getRootElements()) {
            if (first == null
                    || ElementNames.qualifiedMemberName(element)
                    .compareTo(ElementNames.qualifiedMemberName(first)) < 0) {
                first = element;
            }
        }
        return first;
    }

    private boolean thriftAnnotationsAvailable() {
        for (ThriftAnnotationDialect dialect : ThriftAnnotationDialect.values()) {
            if (element(dialect.thriftStruct()) != null
                    || element(dialect.thriftUnion()) != null
                    || element(dialect.thriftEnum()) != null
                    || element(dialect.thriftField()) != null
                    || element(dialect.thriftEnumValue()) != null) {
                return true;
            }
        }
        return false;
    }

    private boolean containsThriftAnnotation(Set<? extends TypeElement> annotations) {
        for (TypeElement annotation : annotations) {
            if (ThriftAnnotations.isSupportedAnnotation(
                    annotation.getQualifiedName().toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsEnumRoot(RoundEnvironment roundEnvironment) {
        for (Element root : roundEnvironment.getRootElements()) {
            if (containsEnum(root)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsEnum(Element element) {
        if (element.getKind() == ElementKind.ENUM) {
            return true;
        }
        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed instanceof TypeElement && containsEnum(enclosed)) {
                return true;
            }
        }
        return false;
    }

    private TypeElement element(String name) {
        return processingEnvironment.getElementUtils().getTypeElement(name);
    }
}
