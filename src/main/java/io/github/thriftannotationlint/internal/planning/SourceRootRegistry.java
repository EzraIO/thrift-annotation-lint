package io.github.thriftannotationlint.internal.planning;

import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maintains the mutually exclusive model/container state of compilation source roots. */
final class SourceRootRegistry {
    private final Map<String, ModelRegistration> models =
            new LinkedHashMap<String, ModelRegistration>();
    private final Map<String, String> modelIdentities =
            new LinkedHashMap<String, String>();
    private final Map<String, ThriftAnnotationDialect> containerDialects =
            new LinkedHashMap<String, ThriftAnnotationDialect>();

    void clear() {
        models.clear();
        modelIdentities.clear();
        containerDialects.clear();
    }

    void registerModel(
            String name,
            SwiftModel.Kind kind,
            ThriftAnnotationDialect dialect,
            String identity) {
        containerDialects.remove(name);
        ModelRegistration registration = new ModelRegistration(name, kind, dialect);
        models.put(registration.key(), registration);
        modelIdentities.put(registration.key(), identity);
    }

    void registerContainer(String name, ThriftAnnotationDialect dialect) {
        if (isModelName(name)) {
            throw new IllegalStateException(
                    "A source model must use the atomic model-to-container migration");
        }
        containerDialects.put(name, dialect);
    }

    boolean migrateModelToContainer(String name, ThriftAnnotationDialect dialect) {
        if (!isModelName(name)) {
            return false;
        }
        for (String key : modelKeys(name)) {
            models.remove(key);
            modelIdentities.remove(key);
        }
        containerDialects.put(name, dialect);
        return true;
    }

    List<String> modelIdentities(String name) {
        List<String> result = new ArrayList<String>();
        for (String key : modelKeys(name)) {
            String identity = modelIdentities.get(key);
            if (identity != null) {
                result.add(identity);
            }
        }
        return result;
    }

    boolean isModelName(String name) {
        for (ModelRegistration registration : models.values()) {
            if (registration.typeName.equals(name)) {
                return true;
            }
        }
        return false;
    }

    boolean isModelIdentity(String identity) {
        return modelIdentities.containsValue(identity);
    }

    boolean isEmpty() {
        return models.isEmpty() && containerDialects.isEmpty();
    }

    Map<String, ModelRegistration> historicalModels() {
        return Collections.unmodifiableMap(
                new LinkedHashMap<String, ModelRegistration>(models));
    }

    Map<String, ThriftAnnotationDialect> historicalContainers() {
        return Collections.unmodifiableMap(
                new LinkedHashMap<String, ThriftAnnotationDialect>(containerDialects));
    }

    private List<String> modelKeys(String name) {
        List<String> result = new ArrayList<String>();
        for (Map.Entry<String, ModelRegistration> entry : models.entrySet()) {
            if (entry.getValue().typeName.equals(name)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }
}
