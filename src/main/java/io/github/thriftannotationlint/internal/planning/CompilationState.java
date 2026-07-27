package io.github.thriftannotationlint.internal.planning;

import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.validation.ModelValidation;

import javax.lang.model.element.Element;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owns all cross-round mutable state for one annotation-processing compilation. */
public final class CompilationState {
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
    private final Map<String, ModelValidation> validationResults =
            new LinkedHashMap<String, ModelValidation>();
    private final Set<String> compilationTypes = new LinkedHashSet<String>();
    private final Map<String, Element> dependencyAnchors =
            new LinkedHashMap<String, Element>();
    private final Map<String, SwiftModel.Kind> pendingModels =
            new LinkedHashMap<String, SwiftModel.Kind>();
    private final SourceRootRegistry sourceRoots = new SourceRootRegistry();
    private final ExactModelBudget exactModelBudget;

    public CompilationState(int maxExactModels) {
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

    public void beginPendingAggregation() {
        pendingModels.clear();
    }

    public void markPending(String typeName, SwiftModel.Kind kind) {
        pendingModels.put(typeName, kind);
    }

    public boolean beginModelValidation(String identity, boolean forceRevalidation) {
        if (!forceRevalidation && processedIdentities.contains(identity)) {
            return false;
        }
        processedIdentities.add(identity);
        return true;
    }

    public void storeResolvedModel(SwiftModel model) {
        resolvedModels.put(model.identity(), model);
    }

    public void removeModel(String identity) {
        resolvedModels.remove(identity);
        validationResults.remove(identity);
    }

    /**
     * Completes a source-root model-to-container transition as one state operation. Once the
     * registry exposes the container state, no model, processed marker, budget reservation, or
     * diagnostic anchor for the former exact model remains observable.
     */
    public boolean migrateSourceModelToContainer(String name, String identity) {
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

    public void releaseModelIdentity(String identity) {
        resolvedModels.remove(identity);
        validationResults.remove(identity);
        processedIdentities.remove(identity);
        exactModelBudget.release(identity);
        dependencyAnchors.remove(identity);
    }

    List<SwiftModel> resolvedModels() {
        return new ArrayList<SwiftModel>(resolvedModels.values());
    }

    public void storeValidationResult(ModelValidation validation) {
        validationResults.put(validation.model().identity(), validation);
    }

    public List<ModelValidation> validationResults() {
        return new ArrayList<ModelValidation>(
                validationResults.values());
    }

    void addCompilationType(String name) {
        compilationTypes.add(name);
    }

    public Set<String> compilationTypes() {
        return Collections.unmodifiableSet(
                new LinkedHashSet<String>(compilationTypes));
    }

    public boolean isCompilationType(String name) {
        return compilationTypes.contains(name);
    }

    public void putDependencyAnchorIfAbsent(String identity, Element anchor) {
        if (!dependencyAnchors.containsKey(identity)) {
            dependencyAnchors.put(identity, anchor);
        }
    }

    public Element dependencyAnchor(String identity) {
        return dependencyAnchors.get(identity);
    }

    public void releaseReferencedIdentity(String identity) {
        exactModelBudget.release(identity);
        dependencyAnchors.remove(identity);
    }

    public ExactModelBudget.Reservation reserveResolvedExactModel(String identity) {
        return exactModelBudget.reserveResolved(identity, sourceRoots.isModelIdentity(identity));
    }

    public int exactModelLimit() {
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

    public boolean isSourceModelName(String name) {
        return sourceRoots.isModelName(name);
    }

    public boolean isSourceModelIdentity(String identity) {
        return sourceRoots.isModelIdentity(identity);
    }

    Map<String, SwiftModel.Kind> historicalSourceModels() {
        return sourceRoots.historicalModels();
    }

    Set<String> historicalSourceContainers() {
        return sourceRoots.historicalContainers();
    }
}
