package io.github.thriftannotationlint;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owns semantic and source-location deduplication for one processing session. */
final class FindingRouter {
    private final CompilationState state;
    private final Set<String> findingKeys = new LinkedHashSet<String>();
    private final Set<String> cycleKeys = new LinkedHashSet<String>();
    private final Set<String> diagnosticLocations = new LinkedHashSet<String>();

    FindingRouter(CompilationState state) {
        this.state = state;
    }

    void add(Finding finding, List<Finding> destination) {
        if (findingKeys.add(finding.sortKey())) {
            destination.add(finding);
        }
    }

    void addCandidateFindings(
            ModelDemand candidate,
            Collection<Finding> findings,
            List<Finding> destination) {
        for (Finding finding : findings) {
            addCandidateFinding(candidate, finding, destination);
        }
    }

    void addCandidateFinding(
            ModelDemand candidate,
            Finding finding,
            List<Finding> destination) {
        if (!findingKeys.add(finding.sortKey())) {
            return;
        }
        String typeName = candidate.type.getQualifiedName().toString();
        if ((candidate.forceRevalidation || !state.isSourceModelName(typeName))
                && candidate.diagnosticAnchor != null) {
            String prefix = candidate.forceRevalidation
                    ? "Revalidated Thrift model '" + candidate.declaredType
                    + "' after generated types became available: "
                    : "Referenced Thrift model '" + candidate.declaredType + "' is invalid: ";
            destination.add(finding.relocated(candidate.diagnosticAnchor, prefix));
        }
        else if (state.isSourceModelName(typeName)
                && finding.element() != null
                && !state.isCompilationType(owningTypeName(finding.element()))) {
            destination.add(finding.relocated(
                    candidate.type,
                    "Inherited classpath metadata is invalid: "));
        }
        else {
            destination.add(finding);
        }
    }

    void addCycleFindings(
            Collection<Finding> findings,
            Map<String, ModelDemand> currentCandidates,
            Element fallbackAnchor,
            List<Finding> destination) {
        for (Finding finding : findings) {
            String cycleKey = finding.semanticDeduplicationKey() == null
                    ? finding.sortKey()
                    : finding.semanticDeduplicationKey();
            if (!cycleKeys.add(cycleKey)) {
                continue;
            }
            String owner = owningTypeName(finding.element());
            if (owner != null && currentCandidates.containsKey(owner)) {
                destination.add(finding);
                continue;
            }
            Element anchor = state.dependencyAnchor(finding.ownerIdentity());
            if (anchor == null) {
                anchor = fallbackAnchor;
            }
            if (anchor != null) {
                destination.add(finding.relocated(
                        anchor,
                        "While validating referenced Thrift models: "));
            }
        }
    }

    void reportAll(
            Messager messager,
            ProcessorMode mode,
            Collection<Finding> findings) {
        new DiagnosticReporter(messager, mode, diagnosticLocations).reportAll(findings);
    }

    private String owningTypeName(Element element) {
        Element current = element;
        while (current != null && !(current instanceof TypeElement)) {
            current = current.getEnclosingElement();
        }
        return current instanceof TypeElement
                ? ((TypeElement) current).getQualifiedName().toString()
                : null;
    }
}
