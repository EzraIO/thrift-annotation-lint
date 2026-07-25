package io.github.thriftannotationlint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable ancestry used to detect exact and expanding generic demand cycles. */
final class DemandPath {
    private final Map<String, List<ExactInstance>> instancesByType;

    private DemandPath(Map<String, List<ExactInstance>> instancesByType) {
        this.instancesByType = new LinkedHashMap<String, List<ExactInstance>>();
        for (Map.Entry<String, List<ExactInstance>> entry : instancesByType.entrySet()) {
            this.instancesByType.put(
                    entry.getKey(),
                    Collections.unmodifiableList(
                            new ArrayList<ExactInstance>(entry.getValue())));
        }
    }

    static DemandPath initial(String typeName, ExactInstance instance) {
        Map<String, List<ExactInstance>> values =
                new LinkedHashMap<String, List<ExactInstance>>();
        values.put(typeName, Collections.singletonList(instance));
        return new DemandPath(values);
    }

    List<ExactInstance> instances(String typeName) {
        return instancesByType.get(typeName);
    }

    DemandPath append(String typeName, ExactInstance instance) {
        Map<String, List<ExactInstance>> copy =
                new LinkedHashMap<String, List<ExactInstance>>();
        for (Map.Entry<String, List<ExactInstance>> entry : instancesByType.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<ExactInstance>(entry.getValue()));
        }
        List<ExactInstance> instances = copy.get(typeName);
        if (instances == null) {
            instances = new ArrayList<ExactInstance>();
            copy.put(typeName, instances);
        }
        instances.add(instance);
        return new DemandPath(copy);
    }
}
