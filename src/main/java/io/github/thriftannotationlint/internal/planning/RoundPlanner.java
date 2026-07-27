package io.github.thriftannotationlint.internal.planning;

import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.model.ThriftAnnotations;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;
import io.github.thriftannotationlint.internal.extract.SwiftModelClassifier;
import io.github.thriftannotationlint.internal.extract.SwiftMemberResolver;
import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.model.ElementNames;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private final SwiftModelClassifier modelClassifier;
    private final DemandClosure demandClosure;
    private final SwiftMemberResolver memberResolver;

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
        this.modelClassifier = modelClassifier;
        this.demandClosure = demandClosure;
        this.memberResolver = memberResolver;
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
        Map<String, ModelRegistration> previousPendingModels =
                roundStart.previousPendingModels();
        boolean rebuildDemandClosure = roundStart.rebuildDemandClosure();

        for (Element root : roundEnvironment.getRootElements()) {
            collectCompilationTypes(root);
        }

        List<Finding> declarationFindings = new ArrayList<Finding>();
        Map<String, ModelDemand> candidates =
                new LinkedHashMap<String, ModelDemand>();
        Map<String, ContainerDemand> containerRoots =
                new LinkedHashMap<String, ContainerDemand>();
        for (ThriftAnnotationDialect dialect : ThriftAnnotationDialect.values()) {
            addAnnotatedTypes(
                    roundEnvironment,
                    dialect.thriftStruct(),
                    SwiftModel.Kind.STRUCT,
                    dialect,
                    candidates,
                    containerRoots);
            addAnnotatedTypes(
                    roundEnvironment,
                    dialect.thriftUnion(),
                    SwiftModel.Kind.UNION,
                    dialect,
                    candidates,
                    containerRoots);
            addAnnotatedTypes(
                    roundEnvironment,
                    dialect.thriftEnum(),
                    SwiftModel.Kind.ENUM,
                    dialect,
                    candidates,
                    containerRoots);
        }
        addEnumValueOwners(roundEnvironment, candidates, declarationFindings);
        Set<String> currentContainerNames =
                new LinkedHashSet<String>(containerRoots.keySet());
        reclassifyPendingContainerTypes(
                candidates,
                containerRoots,
                previousPendingModels);

        addHistoricalContainerRoots(containerRoots);
        if (rebuildDemandClosure) {
            forceCurrentCandidates(candidates);
        }

        List<ModelDemand> orderedModels =
                new ArrayList<ModelDemand>(candidates.values());
        Collections.sort(orderedModels, new Comparator<ModelDemand>() {
            @Override
            public int compare(ModelDemand left, ModelDemand right) {
                return left.type.getQualifiedName().toString()
                        .compareTo(right.type.getQualifiedName().toString());
            }
        });
        List<ContainerDemand> orderedContainers = orderedContainers(
                containerRoots,
                currentContainerNames);

        // Register every current source model before any model validation begins. This ensures
        // dependency relocation and source-budget exemptions never depend on source ordering.
        for (ModelDemand candidate : orderedModels) {
            String sourceName = candidate.type.getQualifiedName().toString();
            state.registerSourceModel(
                    sourceName,
                    candidate.kind,
                    candidate.dialect,
                    candidate.cacheKey());
        }

        Element roundAnchor = firstRootElement(roundEnvironment);
        if (rebuildDemandClosure) {
            appendHistoricalModelRoots(candidates, orderedModels, roundAnchor);
        }

        return new Plan(
                candidates,
                orderedModels,
                orderedContainers,
                declarationFindings,
                roundAnchor,
                rebuildDemandClosure);
    }

    private List<ContainerDemand> orderedContainers(
            Map<String, ContainerDemand> containerRoots,
            Set<String> currentContainerNames) {
        List<ContainerDemand> current = new ArrayList<ContainerDemand>();
        List<ContainerDemand> historical = new ArrayList<ContainerDemand>();
        for (Map.Entry<String, ContainerDemand> entry : containerRoots.entrySet()) {
            if (currentContainerNames.contains(entry.getKey())) {
                current.add(entry.getValue());
            }
            else {
                historical.add(entry.getValue());
            }
        }
        Comparator<ContainerDemand> byQualifiedName =
                new Comparator<ContainerDemand>() {
                    @Override
                    public int compare(ContainerDemand left, ContainerDemand right) {
                        return left.element.getQualifiedName().toString()
                                .compareTo(right.element.getQualifiedName().toString());
                    }
                };
        Collections.sort(current, byQualifiedName);
        Collections.sort(historical, byQualifiedName);
        current.addAll(historical);
        return current;
    }

    private void addEnumUnknownValueOwners(
            RoundEnvironment roundEnvironment,
            ThriftAnnotationDialect dialect,
            Map<String, ModelDemand> candidates) {
        String annotationName = dialect.thriftEnumUnknownValue();
        TypeElement annotation = annotationName == null ? null : element(annotationName);
        if (annotation == null) {
            return;
        }
        for (Element annotated : roundEnvironment.getElementsAnnotatedWith(annotation)) {
            Element owner = annotated.getEnclosingElement();
            if (owner instanceof TypeElement && owner.getKind() == ElementKind.ENUM) {
                addCandidate(
                        (TypeElement) owner, SwiftModel.Kind.ENUM, dialect, candidates);
            }
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
            String name = annotation.getQualifiedName().toString();
            if (ThriftAnnotations.isSupportedAnnotation(name)) {
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
            TypeElement type = (TypeElement) annotated;
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
                containerRoots.put(typeName, new ContainerDemand(
                        type, containerType, dialect));
            }
            else {
                addCandidate(type, kind, dialect, candidates);
            }
        }
    }

    private void reclassifyPendingContainerTypes(
            Map<String, ModelDemand> candidates,
            Map<String, ContainerDemand> containerRoots,
            Map<String, ModelRegistration> previousPendingModels) {
        List<Map.Entry<String, ModelRegistration>> knownModels =
                new ArrayList<Map.Entry<String, ModelRegistration>>(
                        state.historicalSourceModels().entrySet());
        for (Map.Entry<String, ModelRegistration> entry : knownModels) {
            ModelRegistration registration = entry.getValue();
            if (!previousPendingModels.containsKey(entry.getKey())
                    || (entry.getValue().kind != SwiftModel.Kind.STRUCT
                    && entry.getValue().kind != SwiftModel.Kind.UNION)) {
                continue;
            }
            String typeName = registration.typeName;
            TypeElement type = element(typeName);
            if (type == null) {
                continue;
            }
            TypeMirror containerType = typeInspector.containerClassificationType(type);
            if (containerType == null) {
                continue;
            }
            String identity = typeInspector.exactJavaTypeIdentity(type.asType());
            String cacheKey = entry.getValue().dialect.name() + "\u0000" + identity;
            candidates.remove(candidateKey(
                    type, registration.kind, registration.dialect));
            if (!state.migrateSourceModelToContainer(
                    typeName, registration.dialect, cacheKey)) {
                continue;
            }
            containerRoots.put(typeName, new ContainerDemand(
                    type, containerType, entry.getValue().dialect));
        }
    }

    private void addHistoricalContainerRoots(
            Map<String, ContainerDemand> containerRoots) {
        for (Map.Entry<String, ThriftAnnotationDialect> historical
                : state.historicalSourceContainers().entrySet()) {
            String containerRootName = historical.getKey();
            if (containerRoots.containsKey(containerRootName)) {
                continue;
            }
            TypeElement containerRoot = element(containerRootName);
            TypeMirror containerType =
                    typeInspector.containerClassificationType(containerRoot);
            if (containerRoot != null && containerType != null) {
                containerRoots.put(
                        containerRootName,
                        new ContainerDemand(
                                containerRoot, containerType, historical.getValue()));
            }
        }
    }

    private void appendHistoricalModelRoots(
            Map<String, ModelDemand> currentCandidates,
            List<ModelDemand> orderedModels,
            Element roundAnchor) {
        for (Map.Entry<String, ModelRegistration> source
                : state.historicalSourceModels().entrySet()) {
            ModelRegistration registration = source.getValue();
            String typeName = registration.typeName;
            TypeElement type = element(typeName);
            if (type != null && currentCandidates.containsKey(candidateKey(
                    type, registration.kind, registration.dialect))) {
                continue;
            }
            if (type == null || type.asType().getKind() != TypeKind.DECLARED) {
                continue;
            }
            orderedModels.add(new ModelDemand(
                    type,
                    (DeclaredType) type.asType(),
                    type.asType(),
                    typeInspector.exactJavaTypeIdentity(type.asType()),
                    registration.dialect,
                    registration.kind,
                    roundAnchor,
                    demandClosure.initialPath(typeName, type.asType()),
                    true));
        }
    }

    private void forceCurrentCandidates(Map<String, ModelDemand> candidates) {
        for (Map.Entry<String, ModelDemand> entry
                : new ArrayList<Map.Entry<String, ModelDemand>>(candidates.entrySet())) {
            ModelDemand candidate = entry.getValue();
            if (candidate.forceRevalidation) {
                continue;
            }
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

    private void addEnumValueOwners(
            RoundEnvironment roundEnvironment,
            Map<String, ModelDemand> candidates,
            List<Finding> findings) {
        for (ThriftAnnotationDialect dialect : ThriftAnnotationDialect.values()) {
            addEnumUnknownValueOwners(roundEnvironment, dialect, candidates);
            TypeElement annotation = element(dialect.thriftEnumValue());
            if (annotation == null) {
                continue;
            }
            for (Element annotated : roundEnvironment.getElementsAnnotatedWith(annotation)) {
                Element owner = annotated.getEnclosingElement();
                if (owner instanceof TypeElement && owner.getKind() == ElementKind.ENUM) {
                    addCandidate((TypeElement) owner, SwiftModel.Kind.ENUM, dialect, candidates);
                }
                else if (owner instanceof TypeElement && !owner.getKind().isInterface()) {
                    findings.add(Finding.error(
                            DiagnosticCode.INVALID_ENUM_VALUE_METHOD,
                            annotated,
                            "@ThriftEnumValue method '" + annotated.getSimpleName()
                                    + "' must be declared by a Java enum or an interface inherited "
                                    + "by an enum."));
                }
            }
        }
        for (Element root : roundEnvironment.getRootElements()) {
            addEnumsWithInheritedValueMethods(root, candidates);
        }
    }

    private void addEnumsWithInheritedValueMethods(
            Element element,
            Map<String, ModelDemand> candidates) {
        if (element instanceof TypeElement && element.getKind() == ElementKind.ENUM) {
            TypeElement enumType = (TypeElement) element;
            for (ExecutableElement method : ElementFilter.methodsIn(
                    memberResolver.allMembers(enumType))) {
                for (ThriftAnnotationDialect dialect : ThriftAnnotationDialect.values()) {
                    if (ThriftAnnotations.has(method, dialect.thriftEnumValue())) {
                        addCandidate(enumType, SwiftModel.Kind.ENUM, dialect, candidates);
                    }
                }
            }
        }
        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed instanceof TypeElement) {
                addEnumsWithInheritedValueMethods(enclosed, candidates);
            }
        }
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

    private void collectCompilationTypes(Element element) {
        if (element instanceof TypeElement) {
            String name = ((TypeElement) element).getQualifiedName().toString();
            if (!name.isEmpty()) {
                state.addCompilationType(name);
            }
        }
        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed instanceof TypeElement) {
                collectCompilationTypes(enclosed);
            }
        }
    }

    private TypeElement element(String name) {
        return processingEnvironment.getElementUtils().getTypeElement(name);
    }
}
