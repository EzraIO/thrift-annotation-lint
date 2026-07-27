package io.github.thriftannotationlint.internal.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable ancestry used to detect exact and expanding generic demand cycles. */
final class DemandPath {
    private final DemandPath parent;
    private final String typeName;
    private final ExactInstance instance;

    private DemandPath(DemandPath parent, String typeName, ExactInstance instance) {
        this.parent = parent;
        this.typeName = typeName;
        this.instance = instance;
    }

    static DemandPath initial(String typeName, ExactInstance instance) {
        return new DemandPath(null, typeName, instance);
    }

    List<ExactInstance> instances(String typeName) {
        List<ExactInstance> result = new ArrayList<ExactInstance>();
        for (DemandPath current = this; current != null; current = current.parent) {
            if (current.typeName.equals(typeName)) {
                result.add(current.instance);
            }
        }
        if (result.isEmpty()) {
            return null;
        }
        Collections.reverse(result);
        return Collections.unmodifiableList(result);
    }

    DemandPath append(String typeName, ExactInstance instance) {
        return new DemandPath(this, typeName, instance);
    }

    DemandPath parent() {
        return parent;
    }
}
