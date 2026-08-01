package io.github.thriftannotationlint.internal.validation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic, stack-safe Tarjan strongly-connected-components traversal. */
final class IterativeStronglyConnectedComponents {
    List<List<String>> find(Map<String, List<ModelDependencyGraph.Edge>> graph) {
        State state = new State();
        for (String root : graph.keySet()) {
            if (!state.indices.containsKey(root)) {
                traverse(root, graph, state);
            }
        }
        return state.components;
    }

    private void traverse(
            String root,
            Map<String, List<ModelDependencyGraph.Edge>> graph,
            State state) {
        List<Frame> frames = new ArrayList<Frame>();
        frames.add(new Frame(root, null));
        while (!frames.isEmpty()) {
            Frame frame = frames.get(frames.size() - 1);
            enter(frame, state);
            List<ModelDependencyGraph.Edge> edges = graph.get(frame.vertex);
            if (visitNextEdge(frame, edges, frames, state)) {
                continue;
            }
            completeFrame(frame, frames, state);
        }
    }

    private void enter(Frame frame, State state) {
        if (frame.entered) {
            return;
        }
        int index = state.nextIndex++;
        state.indices.put(frame.vertex, index);
        state.lowLinks.put(frame.vertex, index);
        state.stack.add(frame.vertex);
        state.onStack.add(frame.vertex);
        frame.entered = true;
    }

    private boolean visitNextEdge(
            Frame frame,
            List<ModelDependencyGraph.Edge> edges,
            List<Frame> frames,
            State state) {
        if (frame.nextEdge >= edges.size()) {
            return false;
        }
        ModelDependencyGraph.Edge edge = edges.get(frame.nextEdge++);
        if (!state.indices.containsKey(edge.target())) {
            frames.add(new Frame(edge.target(), frame.vertex));
        }
        else if (state.onStack.contains(edge.target())) {
            state.lowLinks.put(
                    frame.vertex,
                    Math.min(state.lowLinks.get(frame.vertex), state.indices.get(edge.target())));
        }
        return true;
    }

    private void completeFrame(Frame frame, List<Frame> frames, State state) {
        frames.remove(frames.size() - 1);
        if (frame.parent != null) {
            state.lowLinks.put(
                    frame.parent,
                    Math.min(state.lowLinks.get(frame.parent), state.lowLinks.get(frame.vertex)));
        }
        if (state.lowLinks.get(frame.vertex).equals(state.indices.get(frame.vertex))) {
            state.components.add(popComponent(frame.vertex, state));
        }
    }

    private List<String> popComponent(String root, State state) {
        List<String> component = new ArrayList<String>();
        while (!state.stack.isEmpty()) {
            String member = state.stack.remove(state.stack.size() - 1);
            state.onStack.remove(member);
            component.add(member);
            if (member.equals(root)) {
                break;
            }
        }
        return component;
    }

    private static final class State {
        private int nextIndex;
        private final Map<String, Integer> indices = new HashMap<String, Integer>();
        private final Map<String, Integer> lowLinks = new HashMap<String, Integer>();
        private final List<String> stack = new ArrayList<String>();
        private final Set<String> onStack = new HashSet<String>();
        private final List<List<String>> components = new ArrayList<List<String>>();
    }

    private static final class Frame {
        private final String vertex;
        private final String parent;
        private int nextEdge;
        private boolean entered;

        private Frame(String vertex, String parent) {
            this.vertex = vertex;
            this.parent = parent;
        }
    }
}
