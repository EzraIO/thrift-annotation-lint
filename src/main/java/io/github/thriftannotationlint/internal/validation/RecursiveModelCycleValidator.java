package io.github.thriftannotationlint.internal.validation;

import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.types.ThriftTypeInspector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Detects unqualified direct model cycles using deterministic iterative traversal. */
final class RecursiveModelCycleValidator {
    private final ModelDependencyGraphBuilder graphBuilder;
    private final IterativeStronglyConnectedComponents components =
            new IterativeStronglyConnectedComponents();
    private final ModelCycleFinder cycleFinder = new ModelCycleFinder();

    RecursiveModelCycleValidator(ThriftTypeInspector typeInspector) {
        this.graphBuilder = new ModelDependencyGraphBuilder(typeInspector);
    }

    List<Finding> validate(List<ModelValidation> validations) {
        ModelDependencyGraph graph = graphBuilder.build(validations);
        List<Finding> findings = new ArrayList<Finding>();
        for (List<String> component : components.find(graph.edgesBySource())) {
            if (!cycleFinder.isCyclic(component, graph.edgesBySource())) {
                continue;
            }
            Set<String> componentNames = new HashSet<String>(component);
            ModelDependencyGraph.Edge representative = cycleFinder.representativeEdge(
                    componentNames, graph.edgesBySource(), graph.vertexOrder());
            List<ModelDependencyGraph.Edge> cycle = cycleFinder.cycleFrom(
                    representative, componentNames, graph.edgesBySource());
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_RECURSIVE_FIELD,
                    representative.part().element(),
                    "Unqualified recursive cycle detected: '" + cycleFinder.format(cycle)
                            + "'. Mark at least one direct edge in this cycle with "
                            + "isRecursive=TRUE.")
                    .withOwnerIdentity(representative.source())
                    .withSemanticDeduplicationKey(cycleFinder.componentKey(component)));
        }
        return findings;
    }
}
