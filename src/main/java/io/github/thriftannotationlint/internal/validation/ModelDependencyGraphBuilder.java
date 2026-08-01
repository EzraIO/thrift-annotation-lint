package io.github.thriftannotationlint.internal.validation;

import io.github.thriftannotationlint.internal.model.FieldPart;
import io.github.thriftannotationlint.internal.model.ResolvedLogicalFields;
import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;
import io.github.thriftannotationlint.internal.types.ThriftTypeInspector;

import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts resolved model metadata into an insertion-ordered exact-model graph. */
final class ModelDependencyGraphBuilder {
    private final ThriftTypeInspector typeInspector;

    ModelDependencyGraphBuilder(ThriftTypeInspector typeInspector) {
        this.typeInspector = typeInspector;
    }

    ModelDependencyGraph build(List<ModelValidation> validations) {
        Map<String, SwiftModel> models = models(validations);
        Map<String, ResolvedLogicalFields> resolvedFields = resolvedFields(validations);
        Map<String, List<ModelDependencyGraph.Edge>> graph = emptyGraph(models);
        Map<String, String> displayNames = displayNames(models);

        for (SwiftModel model : models.values()) {
            addModelEdges(
                    model,
                    resolvedFields.get(model.cacheKey()),
                    models.keySet(),
                    displayNames,
                    graph);
        }
        return new ModelDependencyGraph(graph, vertexOrder(graph));
    }

    private Map<String, SwiftModel> models(List<ModelValidation> validations) {
        Map<String, SwiftModel> models = new LinkedHashMap<String, SwiftModel>();
        for (ModelValidation validation : validations) {
            SwiftModel model = validation.model();
            if (model.kind() != SwiftModel.Kind.ENUM) {
                models.put(model.cacheKey(), model);
            }
        }
        return models;
    }

    private Map<String, ResolvedLogicalFields> resolvedFields(
            List<ModelValidation> validations) {
        Map<String, ResolvedLogicalFields> fields =
                new LinkedHashMap<String, ResolvedLogicalFields>();
        for (ModelValidation validation : validations) {
            if (validation.model().kind() != SwiftModel.Kind.ENUM) {
                fields.put(validation.model().cacheKey(), validation.resolvedFields());
            }
        }
        return fields;
    }

    private Map<String, List<ModelDependencyGraph.Edge>> emptyGraph(
            Map<String, SwiftModel> models) {
        Map<String, List<ModelDependencyGraph.Edge>> graph =
                new LinkedHashMap<String, List<ModelDependencyGraph.Edge>>();
        for (String identity : models.keySet()) {
            graph.put(identity, new ArrayList<ModelDependencyGraph.Edge>());
        }
        return graph;
    }

    private Map<String, String> displayNames(Map<String, SwiftModel> models) {
        Map<String, String> names = new LinkedHashMap<String, String>();
        for (Map.Entry<String, SwiftModel> entry : models.entrySet()) {
            names.put(entry.getKey(), entry.getValue().declaredType().toString());
        }
        return names;
    }

    private void addModelEdges(
            SwiftModel model,
            ResolvedLogicalFields resolvedFields,
            Set<String> knownModels,
            Map<String, String> displayNames,
            Map<String, List<ModelDependencyGraph.Edge>> graph) {
        for (ResolvedLogicalFields.LogicalField field : resolvedFields.fields()) {
            if (field.isRecursiveReference()) {
                continue;
            }
            Map<String, FieldPart> targets = targets(model, field, knownModels);
            for (Map.Entry<String, FieldPart> target : targets.entrySet()) {
                graph.get(model.cacheKey()).add(new ModelDependencyGraph.Edge(
                        model.cacheKey(),
                        target.getKey(),
                        displayNames.get(model.cacheKey()),
                        displayNames.get(target.getKey()),
                        field.displayName(),
                        target.getValue()));
            }
        }
    }

    private Map<String, FieldPart> targets(
            SwiftModel model,
            ResolvedLogicalFields.LogicalField field,
            Set<String> knownModels) {
        Map<String, FieldPart> targets = new LinkedHashMap<String, FieldPart>();
        for (FieldPart part : field.parts()) {
            String targetName = directModelName(
                    model.type(), part.javaType(), model.dialect(), knownModels);
            if (targetName != null
                    && knownModels.contains(targetName)
                    && !targets.containsKey(targetName)) {
                targets.put(targetName, part);
            }
        }
        return targets;
    }

    private Map<String, Integer> vertexOrder(
            Map<String, List<ModelDependencyGraph.Edge>> graph) {
        Map<String, Integer> order = new HashMap<String, Integer>();
        int index = 0;
        for (String vertex : graph.keySet()) {
            order.put(vertex, index++);
        }
        return order;
    }

    private String directModelName(
            TypeElement source,
            TypeMirror type,
            ThriftAnnotationDialect dialect,
            Set<String> knownModels) {
        if (type == null) {
            return null;
        }
        String candidate = dialect.name() + "\u0000" + typeInspector.exactJavaTypeIdentity(type);
        if (knownModels.contains(candidate)) {
            return candidate;
        }
        if (type.getKind() == TypeKind.TYPEVAR && !typeInspector.isModelTypeVariable(type)) {
            return directModelName(
                    source, ((TypeVariable) type).getUpperBound(), dialect, knownModels);
        }
        if (type.getKind() == TypeKind.INTERSECTION) {
            List<? extends TypeMirror> bounds = ((IntersectionType) type).getBounds();
            return bounds.isEmpty()
                    ? null
                    : directModelName(source, bounds.get(0), dialect, knownModels);
        }
        if (!isDirectModelCandidate(type)) {
            return null;
        }

        Element element = ((DeclaredType) type).asElement();
        if (element instanceof TypeElement) {
            candidate = dialect.name() + "\u0000"
                    + ((TypeElement) element).getQualifiedName().toString();
        }
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

    private boolean isDirectModelCandidate(TypeMirror type) {
        return (type.getKind() == TypeKind.DECLARED || type.getKind() == TypeKind.ERROR)
                && (type.getKind() != TypeKind.DECLARED || !typeInspector.isContainerType(type));
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
}
