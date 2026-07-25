package io.github.thriftannotationlint;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds the deterministic, exact generic model demand closure. */
final class DemandClosure {
    /** Insertion-ordered exact-identity queue owned by the closure subsystem. */
    static final class WorkQueue {
        private final List<ModelDemand> demands = new ArrayList<ModelDemand>();
        private final Set<String> scheduledIdentities = new LinkedHashSet<String>();

        private WorkQueue() {
        }

        int size() {
            return demands.size();
        }

        ModelDemand get(int index) {
            return demands.get(index);
        }
    }

    /** Result of expanding one referenced model into the exact-demand work queue. */
    static final class Expansion {
        private final ModelDemand demand;
        private final Finding finding;

        private Expansion(ModelDemand demand, Finding finding) {
            this.demand = demand;
            this.finding = finding;
        }

        ModelDemand demand() {
            return demand;
        }

        Finding finding() {
            return finding;
        }
    }

    private final SwiftTypeInspector typeInspector;
    private final SwiftModelClassifier modelClassifier;

    DemandClosure(
            SwiftTypeInspector typeInspector,
            SwiftModelClassifier modelClassifier) {
        this.typeInspector = typeInspector;
        this.modelClassifier = modelClassifier;
    }

    WorkQueue newQueue() {
        return new WorkQueue();
    }

    void schedule(ModelDemand demand, WorkQueue queue) {
        if (queue.scheduledIdentities.add(demand.identity)) {
            queue.demands.add(demand);
        }
    }

    DemandPath initialPath(String typeName, TypeMirror type) {
        return DemandPath.initial(
                typeName,
                new ExactInstance(
                        typeInspector.exactJavaTypeIdentity(type),
                        typeComplexity(type)));
    }

    Expansion expandAndSchedule(
            ModelDemand owner,
            FieldPart part,
            ModelReference modelReference,
            WorkQueue queue) {
        DeclaredType reference = modelReference.modelView;
        Element element = reference.asElement();
        if (!(element instanceof TypeElement)) {
            return new Expansion(null, null);
        }
        TypeElement referencedType = (TypeElement) element;
        SwiftModel.Kind kind = modelClassifier.modelKind(referencedType);
        if (kind == null) {
            return new Expansion(null, null);
        }

        String identity = typeInspector.exactJavaTypeIdentity(
                modelReference.requestedType);
        String referencedName = referencedType.getQualifiedName().toString();
        int targetComplexity = typeComplexity(modelReference.requestedType);
        List<ExactInstance> ancestorInstances = owner.path.instances(referencedName);
        if (ancestorInstances != null) {
            for (ExactInstance instance : ancestorInstances) {
                if (instance.identity.equals(identity)) {
                    // Swift's deferred cache closes an exact recursion without another instance.
                    return new Expansion(null, null);
                }
            }
            int growthSteps = consecutiveGrowthSteps(
                    ancestorInstances, targetComplexity);
            int transientCapacity = Math.max(
                    1, referencedType.getTypeParameters().size());
            if (growthSteps > transientCapacity) {
                return new Expansion(null, Finding.error(
                        DiagnosticCode.INVALID_RECURSIVE_FIELD,
                        part.element(),
                        "Recursive generic model '" + referencedName
                                + "' keeps producing deeper exact type instances "
                                + "after all type-parameter positions have been "
                                + "traversed; Swift's metadata cache cannot "
                                + "converge."));
            }
        }

        Element anchor = owner.diagnosticAnchor == null
                ? part.element()
                : owner.diagnosticAnchor;
        ModelDemand demand = new ModelDemand(
                referencedType,
                reference,
                modelReference.requestedType,
                identity,
                kind,
                anchor,
                owner.path.append(
                        referencedName,
                        new ExactInstance(identity, targetComplexity)),
                owner.forceRevalidation);
        schedule(demand, queue);
        return new Expansion(demand, null);
    }

    ModelDemand scheduleRootReference(
            ModelReference modelReference,
            Element anchor,
            boolean forceRevalidation,
            WorkQueue queue) {
        DeclaredType reference = modelReference.modelView;
        Element element = reference.asElement();
        if (!(element instanceof TypeElement)) {
            return null;
        }
        TypeElement referencedType = (TypeElement) element;
        SwiftModel.Kind kind = modelClassifier.modelKind(referencedType);
        if (kind == null) {
            return null;
        }
        String identity = typeInspector.exactJavaTypeIdentity(
                modelReference.requestedType);
        String referencedName = referencedType.getQualifiedName().toString();
        ModelDemand demand = new ModelDemand(
                referencedType,
                reference,
                modelReference.requestedType,
                identity,
                kind,
                anchor,
                initialPath(referencedName, modelReference.requestedType),
                forceRevalidation);
        schedule(demand, queue);
        return demand;
    }

    private int consecutiveGrowthSteps(
            List<ExactInstance> ancestors,
            int targetComplexity) {
        int steps = 0;
        int nextComplexity = targetComplexity;
        for (int index = ancestors.size() - 1; index >= 0; index--) {
            int previousComplexity = ancestors.get(index).complexity;
            if (previousComplexity >= nextComplexity) {
                break;
            }
            steps++;
            nextComplexity = previousComplexity;
        }
        return steps;
    }

    private int typeComplexity(TypeMirror type) {
        return typeComplexity(type, new LinkedHashSet<String>());
    }

    List<ModelReference> references(TypeMirror type) {
        List<ModelReference> references = new ArrayList<ModelReference>();
        collectReferencedModelTypes(type, references, new LinkedHashSet<String>());
        return references;
    }

    private int typeComplexity(TypeMirror type, Set<String> visiting) {
        if (type == null || type.getKind() == TypeKind.NONE) {
            return 0;
        }
        String key = type.getKind() + ":" + type;
        if (!visiting.add(key)) {
            return 0;
        }
        try {
            int complexity = 1;
            if (type.getKind() == TypeKind.DECLARED) {
                DeclaredType declared = (DeclaredType) type;
                complexity += typeComplexity(declared.getEnclosingType(), visiting);
                for (TypeMirror argument : declared.getTypeArguments()) {
                    complexity += typeComplexity(argument, visiting);
                }
            }
            else if (type.getKind() == TypeKind.ARRAY) {
                complexity += typeComplexity(
                        ((javax.lang.model.type.ArrayType) type).getComponentType(), visiting);
            }
            else if (type.getKind() == TypeKind.WILDCARD) {
                WildcardType wildcard = (WildcardType) type;
                complexity += typeComplexity(wildcard.getExtendsBound(), visiting);
                complexity += typeComplexity(wildcard.getSuperBound(), visiting);
            }
            else if (type.getKind() == TypeKind.INTERSECTION) {
                for (TypeMirror bound : ((IntersectionType) type).getBounds()) {
                    complexity += typeComplexity(bound, visiting);
                }
            }
            return complexity;
        }
        finally {
            visiting.remove(key);
        }
    }

    private void collectReferencedModelTypes(
            TypeMirror type,
            List<ModelReference> references,
            Set<String> visiting) {
        if (type == null || type.getKind() == TypeKind.ERROR) {
            return;
        }
        String visitKey = type.getKind() + ":" + type;
        if (!visiting.add(visitKey)) {
            return;
        }
        try {
            if (type.getKind() == TypeKind.TYPEVAR) {
                if (!typeInspector.isModelTypeVariable(type)) {
                    TypeMirror bound = firstUpperBound(((TypeVariable) type).getUpperBound());
                    if (!addModelReference(type, bound, references)) {
                        collectReferencedModelTypes(bound, references, visiting);
                    }
                }
                return;
            }
            if (type.getKind() == TypeKind.WILDCARD) {
                WildcardType wildcard = (WildcardType) type;
                TypeMirror bound = wildcard.getExtendsBound() == null
                        ? wildcard.getSuperBound()
                        : wildcard.getExtendsBound();
                if (!addModelReference(type, firstUpperBound(bound), references)) {
                    collectReferencedModelTypes(bound, references, visiting);
                }
                return;
            }
            if (type.getKind() == TypeKind.INTERSECTION) {
                List<? extends TypeMirror> bounds = ((IntersectionType) type).getBounds();
                if (!bounds.isEmpty()) {
                    collectReferencedModelTypes(bounds.get(0), references, visiting);
                }
                return;
            }
            if (type.getKind() != TypeKind.DECLARED) {
                return;
            }

            DeclaredType declared = (DeclaredType) type;
            Element element = declared.asElement();
            TypeElement typeElement = element instanceof TypeElement
                    ? (TypeElement) element
                    : null;
            if (typeElement != null && typeElement.getKind() == ElementKind.ENUM) {
                references.add(new ModelReference(declared, declared));
                return;
            }
            if (typeInspector.isContainerType(type)) {
                for (TypeMirror argument : typeInspector.containerTypeArguments(type)) {
                    collectReferencedModelTypes(argument, references, visiting);
                }
                return;
            }
            if (typeElement != null && modelClassifier.modelKind(typeElement) != null) {
                references.add(new ModelReference(declared, declared));
            }
        }
        finally {
            visiting.remove(visitKey);
        }
    }

    private boolean addModelReference(
            TypeMirror requestedType,
            TypeMirror modelView,
            List<ModelReference> references) {
        if (modelView == null || modelView.getKind() != TypeKind.DECLARED) {
            return false;
        }
        DeclaredType declared = (DeclaredType) modelView;
        Element element = declared.asElement();
        if (!(element instanceof TypeElement)) {
            return false;
        }
        TypeElement typeElement = (TypeElement) element;
        if (typeElement.getKind() != ElementKind.ENUM
                && typeInspector.isContainerType(modelView)) {
            return false;
        }
        if (modelClassifier.modelKind(typeElement) == null) {
            return false;
        }
        references.add(new ModelReference(requestedType, declared));
        return true;
    }

    private TypeMirror firstUpperBound(TypeMirror bound) {
        if (bound != null && bound.getKind() == TypeKind.INTERSECTION) {
            List<? extends TypeMirror> bounds = ((IntersectionType) bound).getBounds();
            return bounds.isEmpty() ? null : bounds.get(0);
        }
        return bound;
    }
}
