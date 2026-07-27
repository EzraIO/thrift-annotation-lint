package io.github.thriftannotationlint.internal.planning;

import io.github.thriftannotationlint.internal.model.SwiftModel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Maintains the mutually exclusive model/container state of compilation source roots. */
final class SourceRootRegistry {
    private final Map<String, SwiftModel.Kind> modelKinds =
            new LinkedHashMap<String, SwiftModel.Kind>();
    private final Map<String, String> modelIdentities =
            new LinkedHashMap<String, String>();
    private final Set<String> containerNames = new LinkedHashSet<String>();

    void clear() {
        modelKinds.clear();
        modelIdentities.clear();
        containerNames.clear();
    }

    void registerModel(String name, SwiftModel.Kind kind, String identity) {
        containerNames.remove(name);
        modelKinds.put(name, kind);
        modelIdentities.put(name, identity);
    }

    void registerContainer(String name) {
        if (modelKinds.containsKey(name)) {
            throw new IllegalStateException(
                    "A source model must use the atomic model-to-container migration");
        }
        containerNames.add(name);
    }

    boolean migrateModelToContainer(String name) {
        if (!modelKinds.containsKey(name)) {
            return false;
        }
        modelKinds.remove(name);
        modelIdentities.remove(name);
        containerNames.add(name);
        return true;
    }

    String modelIdentity(String name) {
        return modelIdentities.get(name);
    }

    boolean isModelName(String name) {
        return modelKinds.containsKey(name);
    }

    boolean isModelIdentity(String identity) {
        return modelIdentities.containsValue(identity);
    }

    boolean isEmpty() {
        return modelKinds.isEmpty() && containerNames.isEmpty();
    }

    Map<String, SwiftModel.Kind> historicalModels() {
        return Collections.unmodifiableMap(
                new LinkedHashMap<String, SwiftModel.Kind>(modelKinds));
    }

    Set<String> historicalContainers() {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(containerNames));
    }
}
