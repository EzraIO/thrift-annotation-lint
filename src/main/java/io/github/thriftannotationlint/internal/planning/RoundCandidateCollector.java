package io.github.thriftannotationlint.internal.planning;

import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.extract.SwiftMemberResolver;
import io.github.thriftannotationlint.internal.extract.SwiftModelClassifier;
import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;
import io.github.thriftannotationlint.internal.model.ThriftAnnotations;
import io.github.thriftannotationlint.internal.types.ThriftTypeInspector;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Collects current and historical model/container candidates for one processing round. */
final class RoundCandidateCollector
        implements RoundEnumCandidateCollector.CandidateRegistrar,
        HistoricalRoundCandidateCollector.CandidateKeyResolver {
    static final class Result {
        private final Map<String, ModelDemand> models;
        private final Map<String, ContainerDemand> containers;
        private final Set<String> currentContainerNames;

        Result(
                Map<String, ModelDemand> models,
                Map<String, ContainerDemand> containers,
                Set<String> currentContainerNames) {
            this.models = models;
            this.containers = containers;
            this.currentContainerNames = currentContainerNames;
        }

        Map<String, ModelDemand> models() {
            return models;
        }

        Map<String, ContainerDemand> containers() {
            return containers;
        }

        Set<String> currentContainerNames() {
            return currentContainerNames;
        }
    }

    private final ProcessingEnvironment environment;
    private final CompilationState state;
    private final ThriftTypeInspector typeInspector;
    private final SwiftModelClassifier modelClassifier;
    private final DemandClosure demandClosure;
    private final SwiftMemberResolver memberResolver;
    private final RoundEnumCandidateCollector enumCandidates;
    private final HistoricalRoundCandidateCollector historicalCandidates;

    RoundCandidateCollector(
            ProcessingEnvironment environment,
            CompilationState state,
            ThriftTypeInspector typeInspector,
            SwiftModelClassifier modelClassifier,
            DemandClosure demandClosure,
            SwiftMemberResolver memberResolver) {
        this.environment = environment;
        this.state = state;
        this.typeInspector = typeInspector;
        this.modelClassifier = modelClassifier;
        this.demandClosure = demandClosure;
        this.memberResolver = memberResolver;
        this.enumCandidates = new RoundEnumCandidateCollector(
                environment, memberResolver, this);
        this.historicalCandidates = new HistoricalRoundCandidateCollector(
                environment, state, typeInspector, demandClosure, this);
    }

    Result collect(
            RoundEnvironment roundEnvironment,
            Map<String, ModelRegistration> previousPendingModels,
            boolean rebuildDemandClosure,
            List<Finding> findings) {
        Map<String, ModelDemand> models = new LinkedHashMap<String, ModelDemand>();
        Map<String, ContainerDemand> containers =
                new LinkedHashMap<String, ContainerDemand>();
        addAnnotatedTypes(roundEnvironment, models, containers);
        enumCandidates.collect(roundEnvironment, models, findings);
        Set<String> currentContainers = new LinkedHashSet<String>(containers.keySet());
        historicalCandidates.reclassifyPendingContainers(
                models, containers, previousPendingModels);
        historicalCandidates.addContainerRoots(containers);
        if (rebuildDemandClosure) {
            forceCurrentCandidates(models);
        }
        return new Result(models, containers, currentContainers);
    }

    void collectCompilationTypes(Set<? extends Element> roots) {
        for (Element root : roots) {
            collectCompilationType(root);
        }
    }

    void appendHistoricalModelRoots(
            Map<String, ModelDemand> currentCandidates,
            List<ModelDemand> orderedModels,
            Element roundAnchor) {
        historicalCandidates.appendModelRoots(
                currentCandidates, orderedModels, roundAnchor);
    }

    private void addAnnotatedTypes(
            RoundEnvironment roundEnvironment,
            Map<String, ModelDemand> models,
            Map<String, ContainerDemand> containers) {
        for (ThriftAnnotationDialect dialect : ThriftAnnotationDialect.values()) {
            addAnnotatedTypes(
                    roundEnvironment, dialect.thriftStruct(), SwiftModel.Kind.STRUCT,
                    dialect, models, containers);
            addAnnotatedTypes(
                    roundEnvironment, dialect.thriftUnion(), SwiftModel.Kind.UNION,
                    dialect, models, containers);
            addAnnotatedTypes(
                    roundEnvironment, dialect.thriftEnum(), SwiftModel.Kind.ENUM,
                    dialect, models, containers);
        }
    }

    private void addAnnotatedTypes(
            RoundEnvironment roundEnvironment,
            String annotationName,
            SwiftModel.Kind kind,
            ThriftAnnotationDialect dialect,
            Map<String, ModelDemand> candidates,
            Map<String, ContainerDemand> containerRoots) {
        TypeElement annotation = element(annotationName);
        if (annotation == null) {
            return;
        }
        for (Element annotated : roundEnvironment.getElementsAnnotatedWith(annotation)) {
            if (!(annotated instanceof TypeElement)) {
                continue;
            }
            registerAnnotatedType(
                    (TypeElement) annotated, kind, dialect, candidates, containerRoots);
        }
    }

    private void registerAnnotatedType(
            TypeElement type,
            SwiftModel.Kind kind,
            ThriftAnnotationDialect dialect,
            Map<String, ModelDemand> candidates,
            Map<String, ContainerDemand> containerRoots) {
        String typeName = type.getQualifiedName().toString();
        TypeMirror containerType = typeInspector.containerClassificationType(type);
        if (type.getKind() == ElementKind.ENUM) {
            addCandidate(type, SwiftModel.Kind.ENUM, dialect, candidates);
        }
        else if ((kind == SwiftModel.Kind.STRUCT || kind == SwiftModel.Kind.UNION)
                && containerType != null) {
            String identity = typeInspector.exactJavaTypeIdentity(type.asType());
            String cacheKey = dialect.name() + "\u0000" + identity;
            if (!state.migrateSourceModelToContainer(typeName, dialect, cacheKey)) {
                state.registerSourceContainer(typeName, dialect);
            }
            containerRoots.put(typeName, new ContainerDemand(type, containerType, dialect));
        }
        else {
            addCandidate(type, kind, dialect, candidates);
        }
    }

    private void forceCurrentCandidates(Map<String, ModelDemand> candidates) {
        for (Map.Entry<String, ModelDemand> entry
                : new ArrayList<Map.Entry<String, ModelDemand>>(candidates.entrySet())) {
            ModelDemand candidate = entry.getValue();
            if (!candidate.forceRevalidation) {
                candidates.put(entry.getKey(), new ModelDemand(
                        candidate.type,
                        candidate.declaredType,
                        candidate.requestedType,
                        candidate.identity,
                        candidate.dialect,
                        candidate.kind,
                        candidate.diagnosticAnchor,
                        candidate.path,
                        true));
            }
        }
    }

    @Override
    public void add(
            TypeElement type,
            SwiftModel.Kind kind,
            ThriftAnnotationDialect dialect,
            Map<String, ModelDemand> candidates) {
        addCandidate(type, kind, dialect, candidates);
    }

    private void addCandidate(
            TypeElement type,
            SwiftModel.Kind kind,
            ThriftAnnotationDialect dialect,
            Map<String, ModelDemand> candidates) {
        String key = candidateKey(type, kind, dialect);
        ModelDemand existing = candidates.get(key);
        if (existing == null
                || modelClassifier.priority(kind) < modelClassifier.priority(existing.kind)) {
            String typeName = type.getQualifiedName().toString();
            candidates.put(key, new ModelDemand(
                    type,
                    (DeclaredType) type.asType(),
                    type.asType(),
                    typeInspector.exactJavaTypeIdentity(type.asType()),
                    dialect,
                    kind,
                    null,
                    demandClosure.initialPath(typeName, type.asType()),
                    false));
        }
    }

    private String candidateKey(
            TypeElement type,
            SwiftModel.Kind kind,
            ThriftAnnotationDialect dialect) {
        String typeName = type.getQualifiedName().toString();
        boolean independentPlainEnum = kind == SwiftModel.Kind.ENUM
                && ThriftAnnotations.dialectFor(type, kind) == null;
        return independentPlainEnum
                ? ModelRegistration.key(typeName, dialect)
                : typeName;
    }

    @Override
    public String key(
            TypeElement type,
            SwiftModel.Kind kind,
            ThriftAnnotationDialect dialect) {
        return candidateKey(type, kind, dialect);
    }

    private void collectCompilationType(Element element) {
        if (element instanceof TypeElement) {
            String name = ((TypeElement) element).getQualifiedName().toString();
            if (!name.isEmpty()) {
                state.addCompilationType(name);
            }
        }
        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed instanceof TypeElement) {
                collectCompilationType(enclosed);
            }
        }
    }

    private TypeElement element(String name) {
        return environment.getElementUtils().getTypeElement(name);
    }
}
