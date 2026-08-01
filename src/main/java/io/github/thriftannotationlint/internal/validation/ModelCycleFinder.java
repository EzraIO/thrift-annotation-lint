package io.github.thriftannotationlint.internal.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Selects and formats the deterministic representative path for a cyclic component. */
final class ModelCycleFinder {
    boolean isCyclic(
            List<String> component,
            Map<String, List<ModelDependencyGraph.Edge>> graph) {
        if (component.size() > 1) {
            return true;
        }
        String onlyVertex = component.get(0);
        for (ModelDependencyGraph.Edge edge : graph.get(onlyVertex)) {
            if (onlyVertex.equals(edge.target())) {
                return true;
            }
        }
        return false;
    }

    ModelDependencyGraph.Edge representativeEdge(
            Set<String> component,
            Map<String, List<ModelDependencyGraph.Edge>> graph,
            Map<String, Integer> vertexOrder) {
        String representativeSource = null;
        int latestIndex = -1;
        for (String vertex : component) {
            int index = vertexOrder.get(vertex);
            if (index > latestIndex) {
                latestIndex = index;
                representativeSource = vertex;
            }
        }
        for (ModelDependencyGraph.Edge edge : graph.get(representativeSource)) {
            if (component.contains(edge.target())) {
                return edge;
            }
        }
        throw new IllegalStateException("Cyclic component does not contain an internal edge");
    }

    List<ModelDependencyGraph.Edge> cycleFrom(
            ModelDependencyGraph.Edge first,
            Set<String> component,
            Map<String, List<ModelDependencyGraph.Edge>> graph) {
        List<ModelDependencyGraph.Edge> cycle = new ArrayList<ModelDependencyGraph.Edge>();
        cycle.add(first);
        if (!first.source().equals(first.target())) {
            boolean found = appendPath(
                    first.target(),
                    first.source(),
                    component,
                    graph,
                    new HashSet<String>(),
                    cycle);
            if (!found) {
                throw new IllegalStateException("Strongly connected component has no return path");
            }
        }
        return cycle;
    }

    String componentKey(List<String> component) {
        List<String> ordered = new ArrayList<String>(component);
        Collections.sort(ordered);
        StringBuilder key = new StringBuilder("CYCLE");
        for (String identity : ordered) {
            key.append('\u0000').append(identity);
        }
        return key.toString();
    }

    String format(List<ModelDependencyGraph.Edge> cycle) {
        StringBuilder message = new StringBuilder(cycle.get(0).sourceDisplayName());
        for (ModelDependencyGraph.Edge edge : cycle) {
            message.append('.').append(edge.fieldName())
                    .append(" -> ").append(edge.targetDisplayName());
        }
        return message.toString();
    }

    private boolean appendPath(
            String current,
            String target,
            Set<String> component,
            Map<String, List<ModelDependencyGraph.Edge>> graph,
            Set<String> visited,
            List<ModelDependencyGraph.Edge> path) {
        if (current.equals(target)) {
            return true;
        }
        visited.add(current);
        List<PathFrame> frames = new ArrayList<PathFrame>();
        frames.add(new PathFrame(current));
        while (!frames.isEmpty()) {
            PathFrame frame = frames.get(frames.size() - 1);
            List<ModelDependencyGraph.Edge> edges = graph.get(frame.vertex);
            if (frame.nextEdge >= edges.size()) {
                frames.remove(frames.size() - 1);
                if (!frames.isEmpty()) {
                    path.remove(path.size() - 1);
                }
                continue;
            }
            ModelDependencyGraph.Edge edge = edges.get(frame.nextEdge++);
            if (!component.contains(edge.target())) {
                continue;
            }
            if (edge.target().equals(target)) {
                path.add(edge);
                return true;
            }
            if (!visited.add(edge.target())) {
                continue;
            }
            path.add(edge);
            frames.add(new PathFrame(edge.target()));
        }
        return false;
    }

    private static final class PathFrame {
        private final String vertex;
        private int nextEdge;

        private PathFrame(String vertex) {
            this.vertex = vertex;
        }
    }
}
