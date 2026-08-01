package io.github.thriftannotationlint.internal.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies the stable current-before-historical ordering contract for round demands. */
final class RoundDemandOrdering {
    List<ModelDemand> models(Map<String, ModelDemand> candidates) {
        List<ModelDemand> ordered = new ArrayList<ModelDemand>(candidates.values());
        Collections.sort(ordered, new Comparator<ModelDemand>() {
            @Override
            public int compare(ModelDemand left, ModelDemand right) {
                return left.type.getQualifiedName().toString()
                        .compareTo(right.type.getQualifiedName().toString());
            }
        });
        return ordered;
    }

    List<ContainerDemand> containers(
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
        Comparator<ContainerDemand> byQualifiedName = new Comparator<ContainerDemand>() {
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
}
