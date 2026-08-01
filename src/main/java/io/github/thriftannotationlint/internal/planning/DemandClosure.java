package io.github.thriftannotationlint.internal.planning;

import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.extract.SwiftModelClassifier;
import io.github.thriftannotationlint.internal.model.FieldPart;
import io.github.thriftannotationlint.internal.model.ModelReference;
import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;
import io.github.thriftannotationlint.internal.model.ThriftAnnotations;
import io.github.thriftannotationlint.internal.types.ThriftTypeInspector;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds the deterministic, exact generic model demand closure. */
public final class DemandClosure {
    /** Insertion-ordered exact-identity queue owned by the closure subsystem. */
    public static final class WorkQueue {
        private final List<ModelDemand> demands = new ArrayList<ModelDemand>();
        private final Set<String> scheduledIdentities = new LinkedHashSet<String>();

        private WorkQueue() {
        }

        public int size() {
            return demands.size();
        }

        public ModelDemand get(int index) {
            return demands.get(index);
        }
    }

    /** Result of expanding one referenced model into the exact-demand work queue. */
    public static final class Expansion {
        private final ModelDemand demand;
        private final Finding finding;

        private Expansion(ModelDemand demand, Finding finding) {
            this.demand = demand;
            this.finding = finding;
        }

        public ModelDemand demand() {
            return demand;
        }

        public Finding finding() {
            return finding;
        }
    }

    private final ThriftTypeInspector typeInspector;
    private final SwiftModelClassifier modelClassifier;
    private final TypeComplexityCalculator complexityCalculator =
            new TypeComplexityCalculator();
    private final ModelReferenceCollector referenceCollector;

    public DemandClosure(
            ThriftTypeInspector typeInspector,
            SwiftModelClassifier modelClassifier) {
        this.typeInspector = typeInspector;
        this.modelClassifier = modelClassifier;
        this.referenceCollector = new ModelReferenceCollector(
                typeInspector, modelClassifier);
    }

    public WorkQueue newQueue() {
        return new WorkQueue();
    }

    public void schedule(ModelDemand demand, WorkQueue queue) {
        if (queue.scheduledIdentities.add(demand.cacheKey())) {
            queue.demands.add(demand);
        }
    }

    DemandPath initialPath(String typeName, TypeMirror type) {
        return DemandPath.initial(
                typeName,
                new ExactInstance(
                        typeInspector.exactJavaTypeIdentity(type),
                        complexityCalculator.measure(type)));
    }

    public Expansion expandAndSchedule(
            ModelDemand owner,
            FieldPart part,
            ModelReference modelReference,
            WorkQueue queue) {
        DeclaredType reference = modelReference.modelView();
        Element element = reference.asElement();
        if (!(element instanceof TypeElement)) {
            return new Expansion(null, null);
        }
        TypeElement referencedType = (TypeElement) element;
        SwiftModel.Kind kind = modelClassifier.modelKind(referencedType);
        if (kind == null) {
            return new Expansion(null, null);
        }
        ThriftAnnotationDialect explicitDialect = ThriftAnnotations.dialectFor(
                referencedType, kind);
        if (explicitDialect != null && explicitDialect != owner.dialect()) {
            return new Expansion(null, Finding.error(
                    DiagnosticCode.MODEL_DECLARATION,
                    part.element(),
                    "Thrift model '" + owner.type().getQualifiedName() + "' uses "
                            + owner.dialect().displayName() + " but references model '"
                            + referencedType.getQualifiedName() + "' declared with "
                            + explicitDialect.displayName() + "."));
        }

        String identity = typeInspector.exactJavaTypeIdentity(
                modelReference.requestedType());
        String referencedName = referencedType.getQualifiedName().toString();
        int targetComplexity = complexityCalculator.measure(modelReference.requestedType());
        List<ExactInstance> ancestorInstances = owner.path.instances(referencedName);
        Finding growthFinding = validateGrowth(
                owner, part, referencedType, identity, referencedName,
                targetComplexity, ancestorInstances);
        if (growthFinding != null || containsExactInstance(ancestorInstances, identity)) {
            return new Expansion(null, growthFinding);
        }

        Element anchor = owner.diagnosticAnchor == null
                ? part.element()
                : owner.diagnosticAnchor;
        ModelDemand demand = new ModelDemand(
                referencedType,
                reference,
                modelReference.requestedType(),
                identity,
                owner.dialect(),
                kind,
                anchor,
                owner.path.append(
                        referencedName,
                        new ExactInstance(identity, targetComplexity)),
                owner.forceRevalidation);
        schedule(demand, queue);
        return new Expansion(demand, null);
    }

    private Finding validateGrowth(
            ModelDemand owner,
            FieldPart part,
            TypeElement referencedType,
            String identity,
            String referencedName,
            int targetComplexity,
            List<ExactInstance> ancestors) {
        if (ancestors == null || containsExactInstance(ancestors, identity)) {
            return null;
        }
        int growthSteps = consecutiveGrowthSteps(ancestors, targetComplexity);
        int transientCapacity = Math.max(1, referencedType.getTypeParameters().size());
        if (growthSteps <= transientCapacity) {
            return null;
        }
        return Finding.error(
                DiagnosticCode.INVALID_RECURSIVE_FIELD,
                part.element(),
                "Recursive generic model '" + referencedName
                        + "' keeps producing deeper exact type instances "
                        + "after all type-parameter positions have been "
                        + "traversed; " + owner.dialect().runtimeName()
                        + "'s metadata cache cannot converge.");
    }

    private boolean containsExactInstance(List<ExactInstance> ancestors, String identity) {
        if (ancestors == null) {
            return false;
        }
        for (ExactInstance instance : ancestors) {
            if (instance.identity.equals(identity)) {
                return true;
            }
        }
        return false;
    }

    public ModelDemand scheduleRootReference(
            ModelReference modelReference,
            Element anchor,
            ThriftAnnotationDialect dialect,
            boolean forceRevalidation,
            WorkQueue queue) {
        DeclaredType reference = modelReference.modelView();
        Element element = reference.asElement();
        if (!(element instanceof TypeElement)) {
            return null;
        }
        TypeElement referencedType = (TypeElement) element;
        SwiftModel.Kind kind = modelClassifier.modelKind(referencedType);
        if (kind == null) {
            return null;
        }
        ThriftAnnotationDialect explicitDialect = ThriftAnnotations.dialectFor(
                referencedType, kind);
        if (explicitDialect != null && explicitDialect != dialect) {
            return null;
        }
        String identity = typeInspector.exactJavaTypeIdentity(
                modelReference.requestedType());
        String referencedName = referencedType.getQualifiedName().toString();
        ModelDemand demand = new ModelDemand(
                referencedType,
                reference,
                modelReference.requestedType(),
                identity,
                dialect,
                kind,
                anchor,
                initialPath(referencedName, modelReference.requestedType()),
                forceRevalidation);
        schedule(demand, queue);
        return demand;
    }

    public Finding rootDialectConflict(
            ModelReference modelReference,
            Element anchor,
            ThriftAnnotationDialect dialect) {
        Element element = modelReference.modelView().asElement();
        if (!(element instanceof TypeElement)) {
            return null;
        }
        TypeElement referencedType = (TypeElement) element;
        SwiftModel.Kind kind = modelClassifier.modelKind(referencedType);
        if (kind == null) {
            return null;
        }
        ThriftAnnotationDialect explicit = ThriftAnnotations.dialectFor(referencedType, kind);
        if (explicit == null || explicit == dialect) {
            return null;
        }
        return Finding.error(
                DiagnosticCode.MODEL_DECLARATION,
                anchor,
                "Annotated container '" + anchor + "' uses " + dialect.displayName()
                        + " but references model '" + referencedType.getQualifiedName()
                        + "' declared with " + explicit.displayName() + ".");
    }

    public List<ModelReference> references(
            TypeMirror type,
            ThriftAnnotationDialect dialect) {
        return referenceCollector.collect(type, dialect);
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
}
