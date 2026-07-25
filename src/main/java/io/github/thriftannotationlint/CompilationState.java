package io.github.thriftannotationlint;

import javax.lang.model.element.Element;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owns all cross-round mutable state for one annotation-processing compilation. */
final class CompilationState {
    static final class RoundStart {
        private final Map<String, SwiftModel.Kind> previousPendingModels;
        private final boolean rebuildDemandClosure;

        private RoundStart(
                Map<String, SwiftModel.Kind> previousPendingModels,
                boolean rebuildDemandClosure) {
            this.previousPendingModels = previousPendingModels;
            this.rebuildDemandClosure = rebuildDemandClosure;
        }

        Map<String, SwiftModel.Kind> previousPendingModels() {
            return previousPendingModels;
        }

        boolean rebuildDemandClosure() {
            return rebuildDemandClosure;
        }
    }

    private int activeRounds;
    private final Set<String> processedIdentities = new LinkedHashSet<String>();
    private final Map<String, SwiftModel> resolvedModels =
            new LinkedHashMap<String, SwiftModel>();
    private final Map<String, SwiftModelValidator.ValidationResult> validationResults =
            new LinkedHashMap<String, SwiftModelValidator.ValidationResult>();
    private final Set<String> compilationTypes = new LinkedHashSet<String>();
    private final Map<String, Element> dependencyAnchors =
            new LinkedHashMap<String, Element>();
    private final Map<String, SwiftModel.Kind> pendingModels =
            new LinkedHashMap<String, SwiftModel.Kind>();
    private final SourceRootRegistry sourceRoots = new SourceRootRegistry();
    private final ExactModelBudget exactModelBudget;

    CompilationState(int maxExactModels) {
        this.exactModelBudget = new ExactModelBudget(maxExactModels);
    }

    RoundStart beginActiveRound() {
        Map<String, SwiftModel.Kind> previous =
                new LinkedHashMap<String, SwiftModel.Kind>(pendingModels);
        // SwiftModel and its resolved logical fields own round-scoped TypeMirror views. Historical
        // roots are deliberately re-extracted below, so retaining either result would cache javac
        // mirrors across rounds.
        resolvedModels.clear();
        validationResults.clear();
        boolean rebuild = activeRounds++ > 0 || !previous.isEmpty();
        return new RoundStart(Collections.unmodifiableMap(previous), rebuild);
    }

    boolean hasPendingModels() {
        return !pendingModels.isEmpty();
    }

    void beginPendingAggregation() {
        pendingModels.clear();
    }

    void markPending(String typeName, SwiftModel.Kind kind) {
        pendingModels.put(typeName, kind);
    }

    boolean beginModelValidation(String identity, boolean forceRevalidation) {
        if (!forceRevalidation && processedIdentities.contains(identity)) {
            return false;
        }
        processedIdentities.add(identity);
        return true;
    }

    void storeResolvedModel(SwiftModel model) {
        resolvedModels.put(model.identity(), model);
    }

    void removeModel(String identity) {
        resolvedModels.remove(identity);
        validationResults.remove(identity);
    }

    /**
     * Completes a source-root model-to-container transition as one state operation. Once the
     * registry exposes the container state, no model, processed marker, budget reservation, or
     * diagnostic anchor for the former exact model remains observable.
     */
    boolean migrateSourceModelToContainer(String name, String identity) {
        String registeredIdentity = sourceRoots.modelIdentity(name);
        if (!sourceRoots.migrateModelToContainer(name)) {
            return false;
        }
        releaseModelIdentity(registeredIdentity);
        if (!identity.equals(registeredIdentity)) {
            // A completed javac placeholder can produce a more precise exact identity in the
            // migration round. Clear both views before exposing the container classification.
            releaseModelIdentity(identity);
        }
        return true;
    }

    void releaseModelIdentity(String identity) {
        resolvedModels.remove(identity);
        validationResults.remove(identity);
        processedIdentities.remove(identity);
        exactModelBudget.release(identity);
        dependencyAnchors.remove(identity);
    }

    List<SwiftModel> resolvedModels() {
        return new ArrayList<SwiftModel>(resolvedModels.values());
    }

    void storeValidationResult(SwiftModelValidator.ValidationResult validation) {
        validationResults.put(validation.model().identity(), validation);
    }

    List<SwiftModelValidator.ValidationResult> validationResults() {
        return new ArrayList<SwiftModelValidator.ValidationResult>(
                validationResults.values());
    }

    void addCompilationType(String name) {
        compilationTypes.add(name);
    }

    Set<String> compilationTypes() {
        return Collections.unmodifiableSet(
                new LinkedHashSet<String>(compilationTypes));
    }

    boolean isCompilationType(String name) {
        return compilationTypes.contains(name);
    }

    void putDependencyAnchorIfAbsent(String identity, Element anchor) {
        if (!dependencyAnchors.containsKey(identity)) {
            dependencyAnchors.put(identity, anchor);
        }
    }

    Element dependencyAnchor(String identity) {
        return dependencyAnchors.get(identity);
    }

    void releaseReferencedIdentity(String identity) {
        exactModelBudget.release(identity);
        dependencyAnchors.remove(identity);
    }

    ExactModelBudget.Reservation reserveResolvedExactModel(String identity) {
        return exactModelBudget.reserveResolved(identity, sourceRoots.isModelIdentity(identity));
    }

    int exactModelLimit() {
        return exactModelBudget.limit();
    }

    boolean hasSourceRoots() {
        return !sourceRoots.isEmpty();
    }

    void registerSourceModel(
            String name,
            SwiftModel.Kind kind,
            String identity) {
        sourceRoots.registerModel(name, kind, identity);
        releaseReferencedIdentity(identity);
    }

    void registerSourceContainer(String name) {
        sourceRoots.registerContainer(name);
    }

    boolean isSourceModelName(String name) {
        return sourceRoots.isModelName(name);
    }

    boolean isSourceModelIdentity(String identity) {
        return sourceRoots.isModelIdentity(identity);
    }

    Map<String, SwiftModel.Kind> historicalSourceModels() {
        return sourceRoots.historicalModels();
    }

    Set<String> historicalSourceContainers() {
        return sourceRoots.historicalContainers();
    }
}
