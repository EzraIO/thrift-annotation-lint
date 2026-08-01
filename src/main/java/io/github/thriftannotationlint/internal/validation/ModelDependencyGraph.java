package io.github.thriftannotationlint.internal.validation;

import io.github.thriftannotationlint.internal.model.FieldPart;

import java.util.List;
import java.util.Map;

/** Ordered exact-model dependency graph used by cycle validation. */
final class ModelDependencyGraph {
    private final Map<String, List<Edge>> edgesBySource;
    private final Map<String, Integer> vertexOrder;

    ModelDependencyGraph(
            Map<String, List<Edge>> edgesBySource,
            Map<String, Integer> vertexOrder) {
        this.edgesBySource = edgesBySource;
        this.vertexOrder = vertexOrder;
    }

    Map<String, List<Edge>> edgesBySource() {
        return edgesBySource;
    }

    Map<String, Integer> vertexOrder() {
        return vertexOrder;
    }

    static final class Edge {
        private final String source;
        private final String target;
        private final String sourceDisplayName;
        private final String targetDisplayName;
        private final String fieldName;
        private final FieldPart part;

        Edge(
                String source,
                String target,
                String sourceDisplayName,
                String targetDisplayName,
                String fieldName,
                FieldPart part) {
            this.source = source;
            this.target = target;
            this.sourceDisplayName = sourceDisplayName;
            this.targetDisplayName = targetDisplayName;
            this.fieldName = fieldName;
            this.part = part;
        }

        String source() {
            return source;
        }

        String target() {
            return target;
        }

        String sourceDisplayName() {
            return sourceDisplayName;
        }

        String targetDisplayName() {
            return targetDisplayName;
        }

        String fieldName() {
            return fieldName;
        }

        FieldPart part() {
            return part;
        }
    }
}
