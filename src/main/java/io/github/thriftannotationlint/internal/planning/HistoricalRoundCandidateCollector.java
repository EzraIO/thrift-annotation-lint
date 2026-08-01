package io.github.thriftannotationlint.internal.planning;

import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;
import io.github.thriftannotationlint.internal.types.ThriftTypeInspector;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Rebuilds and reclassifies source roots retained from earlier processing rounds. */
final class HistoricalRoundCandidateCollector {
    interface CandidateKeyResolver {
        String key(
                TypeElement type,
                SwiftModel.Kind kind,
                ThriftAnnotationDialect dialect);
    }

    private final ProcessingEnvironment environment;
    private final CompilationState state;
    private final ThriftTypeInspector typeInspector;
    private final DemandClosure demandClosure;
    private final CandidateKeyResolver keyResolver;

    HistoricalRoundCandidateCollector(
            ProcessingEnvironment environment,
            CompilationState state,
            ThriftTypeInspector typeInspector,
            DemandClosure demandClosure,
            CandidateKeyResolver keyResolver) {
        this.environment = environment;
        this.state = state;
        this.typeInspector = typeInspector;
        this.demandClosure = demandClosure;
        this.keyResolver = keyResolver;
    }

    void reclassifyPendingContainers(
            Map<String, ModelDemand> candidates,
            Map<String, ContainerDemand> containerRoots,
            Map<String, ModelRegistration> previousPendingModels) {
        List<Map.Entry<String, ModelRegistration>> knownModels =
                new ArrayList<Map.Entry<String, ModelRegistration>>(
                        state.historicalSourceModels().entrySet());
        for (Map.Entry<String, ModelRegistration> entry : knownModels) {
            reclassifyPendingContainer(
                    entry, candidates, containerRoots, previousPendingModels);
        }
    }

    void addContainerRoots(Map<String, ContainerDemand> containerRoots) {
        for (Map.Entry<String, ThriftAnnotationDialect> historical
                : state.historicalSourceContainers().entrySet()) {
            String name = historical.getKey();
            if (containerRoots.containsKey(name)) {
                continue;
            }
            TypeElement root = element(name);
            TypeMirror containerType = typeInspector.containerClassificationType(root);
            if (root != null && containerType != null) {
                containerRoots.put(
                        name, new ContainerDemand(root, containerType, historical.getValue()));
            }
        }
    }

    void appendModelRoots(
            Map<String, ModelDemand> currentCandidates,
            List<ModelDemand> orderedModels,
            Element roundAnchor) {
        for (Map.Entry<String, ModelRegistration> source
                : state.historicalSourceModels().entrySet()) {
            ModelRegistration registration = source.getValue();
            TypeElement type = element(registration.typeName);
            if (isCurrentCandidate(type, registration, currentCandidates)) {
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
                    demandClosure.initialPath(registration.typeName, type.asType()),
                    true));
        }
    }

    private void reclassifyPendingContainer(
            Map.Entry<String, ModelRegistration> entry,
            Map<String, ModelDemand> candidates,
            Map<String, ContainerDemand> containerRoots,
            Map<String, ModelRegistration> previousPendingModels) {
        ModelRegistration registration = entry.getValue();
        if (!previousPendingModels.containsKey(entry.getKey())
                || (registration.kind != SwiftModel.Kind.STRUCT
                && registration.kind != SwiftModel.Kind.UNION)) {
            return;
        }
        TypeElement type = element(registration.typeName);
        TypeMirror containerType = typeInspector.containerClassificationType(type);
        if (type == null || containerType == null) {
            return;
        }
        String identity = typeInspector.exactJavaTypeIdentity(type.asType());
        String cacheKey = registration.dialect.name() + "\u0000" + identity;
        candidates.remove(keyResolver.key(type, registration.kind, registration.dialect));
        if (state.migrateSourceModelToContainer(
                registration.typeName, registration.dialect, cacheKey)) {
            containerRoots.put(
                    registration.typeName,
                    new ContainerDemand(type, containerType, registration.dialect));
        }
    }

    private boolean isCurrentCandidate(
            TypeElement type,
            ModelRegistration registration,
            Map<String, ModelDemand> currentCandidates) {
        return type != null && currentCandidates.containsKey(keyResolver.key(
                type, registration.kind, registration.dialect));
    }

    private TypeElement element(String name) {
        return environment.getElementUtils().getTypeElement(name);
    }
}
