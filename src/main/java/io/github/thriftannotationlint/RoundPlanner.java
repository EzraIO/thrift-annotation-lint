package io.github.thriftannotationlint;

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
final class RoundPlanner {
    static final class Plan {
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

        Map<String, ModelDemand> currentCandidates() {
            return currentCandidates;
        }

        List<ModelDemand> modelDemands() {
            return modelDemands;
        }

        List<ContainerDemand> containerDemands() {
            return containerDemands;
        }

        List<Finding> declarationFindings() {
            return declarationFindings;
        }

        Element diagnosticAnchor() {
            return diagnosticAnchor;
        }

        boolean rebuildDemandClosure() {
            return rebuildDemandClosure;
        }
    }

    private final ProcessingEnvironment processingEnvironment;
    private final CompilationState state;
    private final SwiftTypeInspector typeInspector;
    private final SwiftModelClassifier modelClassifier;
    private final DemandClosure demandClosure;

    RoundPlanner(
            ProcessingEnvironment processingEnvironment,
            CompilationState state,
            SwiftTypeInspector typeInspector,
            SwiftModelClassifier modelClassifier,
            DemandClosure demandClosure) {
        this.processingEnvironment = processingEnvironment;
        this.state = state;
        this.typeInspector = typeInspector;
        this.modelClassifier = modelClassifier;
        this.demandClosure = demandClosure;
    }

    boolean isRelevant(
            Set<? extends TypeElement> annotations,
            RoundEnvironment roundEnvironment) {
        if (!swiftAnnotationsAvailable()) {
            return false;
        }
        return containsEnumRoot(roundEnvironment)
                || containsSwiftAnnotation(annotations)
                || state.hasSourceRoots()
                || state.hasPendingModels();
    }

    Plan plan(RoundEnvironment roundEnvironment) {
        CompilationState.RoundStart roundStart = state.beginActiveRound();
        Map<String, SwiftModel.Kind> previousPendingModels =
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
        addAnnotatedTypes(
                roundEnvironment,
                SwiftAnnotations.THRIFT_STRUCT,
                SwiftModel.Kind.STRUCT,
                candidates,
                containerRoots);
        addAnnotatedTypes(
                roundEnvironment,
                SwiftAnnotations.THRIFT_UNION,
                SwiftModel.Kind.UNION,
                candidates,
                containerRoots);
        addAnnotatedTypes(
                roundEnvironment,
                SwiftAnnotations.THRIFT_ENUM,
                SwiftModel.Kind.ENUM,
                candidates,
                containerRoots);
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
                    candidate.identity);
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

    Element firstRootElement(RoundEnvironment roundEnvironment) {
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

    private boolean swiftAnnotationsAvailable() {
        return element(SwiftAnnotations.THRIFT_STRUCT) != null
                || element(SwiftAnnotations.THRIFT_UNION) != null
                || element(SwiftAnnotations.THRIFT_ENUM) != null
                || element(SwiftAnnotations.THRIFT_FIELD) != null
                || element(SwiftAnnotations.THRIFT_ENUM_VALUE) != null;
    }

    private boolean containsSwiftAnnotation(Set<? extends TypeElement> annotations) {
        for (TypeElement annotation : annotations) {
            String name = annotation.getQualifiedName().toString();
            if (SwiftAnnotations.THRIFT_STRUCT.equals(name)
                    || SwiftAnnotations.THRIFT_FIELD.equals(name)
                    || SwiftAnnotations.THRIFT_CONSTRUCTOR.equals(name)
                    || SwiftAnnotations.THRIFT_UNION.equals(name)
                    || SwiftAnnotations.THRIFT_UNION_ID.equals(name)
                    || SwiftAnnotations.THRIFT_ENUM.equals(name)
                    || SwiftAnnotations.THRIFT_ENUM_VALUE.equals(name)) {
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
                addCandidate(type, SwiftModel.Kind.ENUM, candidates);
            }
            else if ((kind == SwiftModel.Kind.STRUCT || kind == SwiftModel.Kind.UNION)
                    && containerType != null) {
                String identity = typeInspector.exactJavaTypeIdentity(type.asType());
                if (!state.migrateSourceModelToContainer(typeName, identity)) {
                    state.registerSourceContainer(typeName);
                }
                containerRoots.put(typeName, new ContainerDemand(type, containerType));
            }
            else {
                addCandidate(type, kind, candidates);
            }
        }
    }

    private void reclassifyPendingContainerTypes(
            Map<String, ModelDemand> candidates,
            Map<String, ContainerDemand> containerRoots,
            Map<String, SwiftModel.Kind> previousPendingModels) {
        List<Map.Entry<String, SwiftModel.Kind>> knownModels =
                new ArrayList<Map.Entry<String, SwiftModel.Kind>>(
                        state.historicalSourceModels().entrySet());
        for (Map.Entry<String, SwiftModel.Kind> entry : knownModels) {
            if (!previousPendingModels.containsKey(entry.getKey())
                    || (entry.getValue() != SwiftModel.Kind.STRUCT
                    && entry.getValue() != SwiftModel.Kind.UNION)) {
                continue;
            }
            String typeName = entry.getKey();
            TypeElement type = element(typeName);
            if (type == null) {
                continue;
            }
            TypeMirror containerType = typeInspector.containerClassificationType(type);
            if (containerType == null) {
                continue;
            }
            String identity = typeInspector.exactJavaTypeIdentity(type.asType());
            candidates.remove(typeName);
            if (!state.migrateSourceModelToContainer(typeName, identity)) {
                continue;
            }
            containerRoots.put(typeName, new ContainerDemand(type, containerType));
        }
    }

    private void addHistoricalContainerRoots(
            Map<String, ContainerDemand> containerRoots) {
        for (String containerRootName : state.historicalSourceContainers()) {
            if (containerRoots.containsKey(containerRootName)) {
                continue;
            }
            TypeElement containerRoot = element(containerRootName);
            TypeMirror containerType =
                    typeInspector.containerClassificationType(containerRoot);
            if (containerRoot != null && containerType != null) {
                containerRoots.put(
                        containerRootName,
                        new ContainerDemand(containerRoot, containerType));
            }
        }
    }

    private void appendHistoricalModelRoots(
            Map<String, ModelDemand> currentCandidates,
            List<ModelDemand> orderedModels,
            Element roundAnchor) {
        for (Map.Entry<String, SwiftModel.Kind> source
                : state.historicalSourceModels().entrySet()) {
            if (currentCandidates.containsKey(source.getKey())) {
                continue;
            }
            TypeElement type = element(source.getKey());
            if (type == null || type.asType().getKind() != TypeKind.DECLARED) {
                continue;
            }
            orderedModels.add(new ModelDemand(
                    type,
                    (DeclaredType) type.asType(),
                    type.asType(),
                    typeInspector.exactJavaTypeIdentity(type.asType()),
                    source.getValue(),
                    roundAnchor,
                    demandClosure.initialPath(source.getKey(), type.asType()),
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
        TypeElement annotation = element(SwiftAnnotations.THRIFT_ENUM_VALUE);
        if (annotation == null) {
            return;
        }
        for (Element annotated : roundEnvironment.getElementsAnnotatedWith(annotation)) {
            Element owner = annotated.getEnclosingElement();
            if (owner instanceof TypeElement && owner.getKind() == ElementKind.ENUM) {
                addCandidate((TypeElement) owner, SwiftModel.Kind.ENUM, candidates);
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
                    processingEnvironment.getElementUtils().getAllMembers(enumType))) {
                if (SwiftAnnotations.has(method, SwiftAnnotations.THRIFT_ENUM_VALUE)) {
                    addCandidate(enumType, SwiftModel.Kind.ENUM, candidates);
                    break;
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
            Map<String, ModelDemand> candidates) {
        String typeName = type.getQualifiedName().toString();
        ModelDemand existing = candidates.get(typeName);
        if (existing == null
                || modelClassifier.priority(kind) < modelClassifier.priority(existing.kind)) {
            candidates.put(typeName, new ModelDemand(
                    type,
                    (DeclaredType) type.asType(),
                    type.asType(),
                    typeInspector.exactJavaTypeIdentity(type.asType()),
                    kind,
                    null,
                    demandClosure.initialPath(typeName, type.asType()),
                    false));
        }
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
