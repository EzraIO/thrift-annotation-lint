package io.github.thriftannotationlint.internal.planning;

import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.model.FieldPart;
import io.github.thriftannotationlint.internal.model.ModelReference;
import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.types.IncompleteTypeGate;
import io.github.thriftannotationlint.internal.types.ThriftTypeInspector;

import javax.lang.model.type.TypeMirror;
import java.util.List;

/** Validates container roots and expands model references into the demand work queue. */
final class ReferenceDemandScheduler {
    private final DemandClosure demandClosure;
    private final ThriftTypeInspector typeInspector;
    private final CompilationState state;
    private final FindingRouter findingRouter;

    ReferenceDemandScheduler(
            DemandClosure demandClosure,
            ThriftTypeInspector typeInspector,
            CompilationState state,
            FindingRouter findingRouter) {
        this.demandClosure = demandClosure;
        this.typeInspector = typeInspector;
        this.state = state;
        this.findingRouter = findingRouter;
    }

    void scheduleContainerRoots(
            List<ContainerDemand> roots,
            DemandClosure.WorkQueue work,
            boolean forceRevalidation,
            IncompleteTypeGate incompleteTypeGate,
            List<Finding> findings) {
        for (ContainerDemand root : roots) {
            if (incompleteTypeGate.containsErrorType(root.element().asType())) {
                continue;
            }
            validateContainerRoot(root, findings);
            scheduleContainerReferences(root, work, forceRevalidation, findings);
        }
    }

    void scheduleModelReferences(
            ModelDemand owner,
            SwiftModel model,
            DemandClosure.WorkQueue work,
            List<Finding> findings) {
        for (FieldPart part : model.fieldParts()) {
            for (ModelReference reference
                    : demandClosure.references(part.javaType(), model.dialect())) {
                DemandClosure.Expansion expansion = demandClosure.expandAndSchedule(
                        owner, part, reference, work);
                if (expansion.finding() != null) {
                    findingRouter.addCandidateFinding(owner, expansion.finding(), findings);
                    continue;
                }
                registerAnchor(expansion.demand());
            }
        }
    }

    void scheduleContainerReferences(
            ContainerDemand root,
            DemandClosure.WorkQueue work,
            boolean forceRevalidation,
            List<Finding> findings) {
        for (ModelReference reference
                : demandClosure.references(root.classificationType(), root.dialect())) {
            Finding conflict = demandClosure.rootDialectConflict(
                    reference, root.element(), root.dialect());
            if (conflict != null) {
                findingRouter.add(conflict, findings);
                continue;
            }
            ModelDemand demand = demandClosure.scheduleRootReference(
                    reference,
                    root.element(),
                    root.dialect(),
                    forceRevalidation,
                    work);
            registerAnchor(demand);
        }
    }

    private void validateContainerRoot(ContainerDemand root, List<Finding> findings) {
        TypeMirror type = root.classificationType();
        if (!typeInspector.isSupported(type, root.dialect())) {
            findingRouter.add(Finding.error(
                    DiagnosticCode.UNSUPPORTED_JAVA_TYPE,
                    root.element(),
                    "Annotated type '" + root.element().getQualifiedName()
                            + "' is classified by " + root.dialect().runtimeName()
                            + " as a container, but its resolved type '" + type
                            + "' contains an unsupported element or key/value type."),
                    findings);
        }
    }

    private void registerAnchor(ModelDemand demand) {
        if (demand == null) {
            return;
        }
        String referencedName = demand.type().getQualifiedName().toString();
        if (!state.isSourceModelName(referencedName)) {
            state.putDependencyAnchorIfAbsent(
                    demand.cacheKey(), demand.diagnosticAnchor());
        }
    }
}
