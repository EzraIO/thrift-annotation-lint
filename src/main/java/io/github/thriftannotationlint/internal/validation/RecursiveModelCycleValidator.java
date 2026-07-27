package io.github.thriftannotationlint.internal.validation;

import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.model.FieldPart;
import io.github.thriftannotationlint.internal.model.ResolvedLogicalFields;
import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.types.ThriftTypeInspector;

import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Detects unqualified direct model cycles using deterministic iterative Tarjan traversal. */
final class RecursiveModelCycleValidator {
    private final ThriftTypeInspector typeInspector;

    RecursiveModelCycleValidator(ThriftTypeInspector typeInspector) {
        this.typeInspector = typeInspector;
    }

    List<Finding> validate(List<ModelValidation> validations) {
        Map<String, SwiftModel> modelsByIdentity =
                new LinkedHashMap<String, SwiftModel>();
        Map<String, ResolvedLogicalFields> fieldsByIdentity =
                new LinkedHashMap<String, ResolvedLogicalFields>();
        for (ModelValidation validation : validations) {
            SwiftModel model = validation.model();
            if (model.kind() != SwiftModel.Kind.ENUM) {
                modelsByIdentity.put(model.cacheKey(), model);
                fieldsByIdentity.put(model.cacheKey(), validation.resolvedFields());
            }
        }

        Map<String, List<ModelEdge>> graph = new LinkedHashMap<String, List<ModelEdge>>();
        Map<String, String> displayNames = new LinkedHashMap<String, String>();
        for (String identity : modelsByIdentity.keySet()) {
            graph.put(identity, new ArrayList<ModelEdge>());
            displayNames.put(identity, modelsByIdentity.get(identity).declaredType().toString());
        }
        // Exact vertices mirror Swift's Type-keyed metadata cache. Demand traversal adds concrete
        // views such as B<T-from-A>, allowing generic cycles to close without falsely rejecting a
        // finite chain such as A<B<String>> -> B<String> -> A<String> -> String.
        for (SwiftModel model : modelsByIdentity.values()) {
            ResolvedLogicalFields resolvedFields = fieldsByIdentity.get(model.cacheKey());
            for (ResolvedLogicalFields.LogicalField field : resolvedFields.fields()) {
                if (field.isRecursiveReference()) {
                    continue;
                }
                Map<String, FieldPart> targets = new LinkedHashMap<String, FieldPart>();
                for (FieldPart part : field.parts()) {
                    String targetName = directModelName(
                            model.type(),
                            part.javaType(),
                            model.dialect(),
                            modelsByIdentity.keySet());
                    if (targetName == null) {
                        continue;
                    }
                    if (modelsByIdentity.containsKey(targetName)
                            && !targets.containsKey(targetName)) {
                        targets.put(targetName, part);
                    }
                }
                for (Map.Entry<String, FieldPart> target : targets.entrySet()) {
                    graph.get(model.cacheKey()).add(new ModelEdge(
                            model.cacheKey(),
                            target.getKey(),
                            displayNames.get(model.cacheKey()),
                            displayNames.get(target.getKey()),
                            field.displayName(),
                            target.getValue()));
                }
            }
        }

        Map<String, Integer> vertexOrder = new HashMap<String, Integer>();
        int vertexIndex = 0;
        for (String vertex : graph.keySet()) {
            vertexOrder.put(vertex, vertexIndex++);
        }

        List<Finding> findings = new ArrayList<Finding>();
        for (List<String> component : stronglyConnectedComponents(graph)) {
            if (!isCyclicComponent(component, graph)) {
                continue;
            }
            Set<String> componentNames = new HashSet<String>(component);
            ModelEdge representative = representativeEdge(componentNames, graph, vertexOrder);
            List<ModelEdge> cycle = cycleFrom(representative, componentNames, graph);
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_RECURSIVE_FIELD,
                    representative.part.element(),
                    "Unqualified recursive cycle detected: '" + formatCycle(cycle)
                            + "'. Mark at least one direct edge in this cycle with "
                            + "isRecursive=TRUE.")
                    .withOwnerIdentity(representative.source)
                    .withSemanticDeduplicationKey(componentKey(component)));
        }
        return findings;
    }

    private String componentKey(List<String> component) {
        List<String> ordered = new ArrayList<String>(component);
        Collections.sort(ordered);
        StringBuilder key = new StringBuilder("CYCLE");
        for (String identity : ordered) {
            key.append('\u0000').append(identity);
        }
        return key.toString();
    }

    private String directModelName(
            TypeElement source,
            TypeMirror type,
            io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect dialect,
            Set<String> knownModels) {
        if (type == null) {
            return null;
        }
        String identity = typeInspector.exactJavaTypeIdentity(type);
        String candidate = dialect.name() + "\u0000" + identity;
        if (knownModels.contains(candidate)) {
            return candidate;
        }

        if (type.getKind() == TypeKind.TYPEVAR
                && !typeInspector.isModelTypeVariable(type)) {
            return directModelName(
                    source,
                    ((javax.lang.model.type.TypeVariable) type).getUpperBound(),
                    dialect,
                    knownModels);
        }
        if (type.getKind() == TypeKind.INTERSECTION) {
            List<? extends TypeMirror> bounds =
                    ((javax.lang.model.type.IntersectionType) type).getBounds();
            return bounds.isEmpty()
                    ? null
                    : directModelName(source, bounds.get(0), dialect, knownModels);
        }
        if ((type.getKind() != TypeKind.DECLARED
                && type.getKind() != TypeKind.ERROR)
                || (type.getKind() == TypeKind.DECLARED
                && typeInspector.isContainerType(type))) {
            return null;
        }

        Element element = ((DeclaredType) type).asElement();

        candidate = element instanceof TypeElement
                ? dialect.name() + "\u0000"
                        + ((TypeElement) element).getQualifiedName().toString()
                : candidate;
        if (knownModels.contains(candidate)) {
            return candidate;
        }

        if (!(element instanceof TypeElement)) {
            return null;
        }
        String packageName = packageName(source);
        String packageRelative = packageName.isEmpty()
                ? candidate
                : dialect.name() + "\u0000" + packageName + "."
                        + ((TypeElement) element).getSimpleName();
        return knownModels.contains(packageRelative) ? packageRelative : null;
    }

    private String packageName(TypeElement type) {
        Element element = type;
        while (element != null && !(element instanceof PackageElement)) {
            element = element.getEnclosingElement();
        }
        return element instanceof PackageElement
                ? ((PackageElement) element).getQualifiedName().toString()
                : "";
    }

    private List<List<String>> stronglyConnectedComponents(
            Map<String, List<ModelEdge>> graph) {
        TarjanState state = new TarjanState();
        for (String root : graph.keySet()) {
            if (state.indices.containsKey(root)) {
                continue;
            }
            List<TarjanFrame> frames = new ArrayList<TarjanFrame>();
            frames.add(new TarjanFrame(root, null));
            while (!frames.isEmpty()) {
                TarjanFrame frame = frames.get(frames.size() - 1);
                if (!frame.entered) {
                    int index = state.nextIndex++;
                    state.indices.put(frame.vertex, index);
                    state.lowLinks.put(frame.vertex, index);
                    state.stack.add(frame.vertex);
                    state.onStack.add(frame.vertex);
                    frame.entered = true;
                }

                List<ModelEdge> edges = graph.get(frame.vertex);
                if (frame.nextEdge < edges.size()) {
                    ModelEdge edge = edges.get(frame.nextEdge++);
                    if (!state.indices.containsKey(edge.target)) {
                        frames.add(new TarjanFrame(edge.target, frame.vertex));
                    }
                    else if (state.onStack.contains(edge.target)) {
                        state.lowLinks.put(
                                frame.vertex,
                                Math.min(
                                        state.lowLinks.get(frame.vertex),
                                        state.indices.get(edge.target)));
                    }
                    continue;
                }

                frames.remove(frames.size() - 1);
                if (frame.parent != null) {
                    state.lowLinks.put(
                            frame.parent,
                            Math.min(
                                    state.lowLinks.get(frame.parent),
                                    state.lowLinks.get(frame.vertex)));
                }
                if (state.lowLinks.get(frame.vertex).equals(
                        state.indices.get(frame.vertex))) {
                    List<String> component = new ArrayList<String>();
                    while (!state.stack.isEmpty()) {
                        String member = state.stack.remove(state.stack.size() - 1);
                        state.onStack.remove(member);
                        component.add(member);
                        if (member.equals(frame.vertex)) {
                            break;
                        }
                    }
                    state.components.add(component);
                }
            }
        }
        return state.components;
    }

    private boolean isCyclicComponent(
            List<String> component,
            Map<String, List<ModelEdge>> graph) {
        if (component.size() > 1) {
            return true;
        }
        String onlyVertex = component.get(0);
        for (ModelEdge edge : graph.get(onlyVertex)) {
            if (onlyVertex.equals(edge.target)) {
                return true;
            }
        }
        return false;
    }

    private ModelEdge representativeEdge(
            Set<String> component,
            Map<String, List<ModelEdge>> graph,
            Map<String, Integer> vertexOrder) {
        // Models are inserted in processing-round order. Prefer the newest source so a cycle that
        // closes in a later round still points to a current-round element with a valid position.
        String representativeSource = null;
        int latestIndex = -1;
        for (String vertex : component) {
            int index = vertexOrder.get(vertex);
            if (index > latestIndex) {
                latestIndex = index;
                representativeSource = vertex;
            }
        }
        for (ModelEdge edge : graph.get(representativeSource)) {
            if (component.contains(edge.target)) {
                return edge;
            }
        }
        throw new IllegalStateException("Cyclic component does not contain an internal edge");
    }

    private List<ModelEdge> cycleFrom(
            ModelEdge first,
            Set<String> component,
            Map<String, List<ModelEdge>> graph) {
        List<ModelEdge> cycle = new ArrayList<ModelEdge>();
        cycle.add(first);
        if (!first.source.equals(first.target)) {
            boolean found = appendPathIteratively(
                    first.target,
                    first.source,
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

    private boolean appendPathIteratively(
            String current,
            String target,
            Set<String> component,
            Map<String, List<ModelEdge>> graph,
            Set<String> visited,
            List<ModelEdge> path) {
        if (current.equals(target)) {
            return true;
        }
        visited.add(current);
        List<PathFrame> frames = new ArrayList<PathFrame>();
        frames.add(new PathFrame(current));
        while (!frames.isEmpty()) {
            PathFrame frame = frames.get(frames.size() - 1);
            List<ModelEdge> edges = graph.get(frame.vertex);
            if (frame.nextEdge >= edges.size()) {
                frames.remove(frames.size() - 1);
                if (!frames.isEmpty()) {
                    path.remove(path.size() - 1);
                }
                continue;
            }
            ModelEdge edge = edges.get(frame.nextEdge++);
            if (!component.contains(edge.target)) {
                continue;
            }
            if (edge.target.equals(target)) {
                path.add(edge);
                return true;
            }
            if (!visited.add(edge.target)) {
                continue;
            }
            path.add(edge);
            frames.add(new PathFrame(edge.target));
        }
        return false;
    }

    private String formatCycle(List<ModelEdge> cycle) {
        StringBuilder message = new StringBuilder(cycle.get(0).sourceDisplayName);
        for (ModelEdge edge : cycle) {
            message.append('.').append(edge.fieldName)
                    .append(" -> ").append(edge.targetDisplayName);
        }
        return message.toString();
    }

    private static final class ModelEdge {
        private final String source;
        private final String target;
        private final String sourceDisplayName;
        private final String targetDisplayName;
        private final String fieldName;
        private final FieldPart part;

        private ModelEdge(
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
    }

    private static final class TarjanState {
        private int nextIndex;
        private final Map<String, Integer> indices = new HashMap<String, Integer>();
        private final Map<String, Integer> lowLinks = new HashMap<String, Integer>();
        private final List<String> stack = new ArrayList<String>();
        private final Set<String> onStack = new HashSet<String>();
        private final List<List<String>> components = new ArrayList<List<String>>();
    }

    private static final class TarjanFrame {
        private final String vertex;
        private final String parent;
        private int nextEdge;
        private boolean entered;

        private TarjanFrame(String vertex, String parent) {
            this.vertex = vertex;
            this.parent = parent;
        }
    }

    private static final class PathFrame {
        private final String vertex;
        private int nextEdge;

        private PathFrame(String vertex) {
            this.vertex = vertex;
        }
    }
}
