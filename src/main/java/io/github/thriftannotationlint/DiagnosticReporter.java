package io.github.thriftannotationlint;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class DiagnosticReporter {
    private final Messager messager;
    private final ProcessorMode mode;
    private final Set<String> reportedLocations;

    DiagnosticReporter(Messager messager, ProcessorMode mode) {
        this(messager, mode, new HashSet<String>());
    }

    DiagnosticReporter(
            Messager messager,
            ProcessorMode mode,
            Set<String> reportedLocations) {
        this.messager = messager;
        this.mode = mode;
        this.reportedLocations = reportedLocations;
    }

    void report(Finding finding) {
        Diagnostic.Kind kind = diagnosticKind(finding);
        if (finding.element() == null) {
            messager.printMessage(kind, finding.formattedMessage());
        }
        else if (finding.annotation() == null) {
            messager.printMessage(kind, finding.formattedMessage(), finding.element());
        }
        else if (finding.annotationValue() == null) {
            messager.printMessage(
                    kind,
                    finding.formattedMessage(),
                    finding.element(),
                    finding.annotation());
        }
        else {
            messager.printMessage(
                    kind,
                    finding.formattedMessage(),
                    finding.element(),
                    finding.annotation(),
                    finding.annotationValue());
        }
    }

    void reportAll(Collection<Finding> findings) {
        List<Finding> ordered = new ArrayList<Finding>(findings);
        Collections.sort(ordered, new Comparator<Finding>() {
            @Override
            public int compare(Finding left, Finding right) {
                int leftPriority = left.code().reportingPriority();
                int rightPriority = right.code().reportingPriority();
                if (leftPriority != rightPriority) {
                    return leftPriority - rightPriority;
                }
                return left.sortKey().compareTo(right.sortKey());
            }
        });
        for (Finding finding : ordered) {
            String location = finding.locationKey();
            if (location != null && !reportedLocations.add(location)) {
                // javac 8 silently drops a later diagnostic with the same preferred position.
                // Preserve every stable code at the nearest unused annotation or enclosing
                // declaration; the highest-priority root cause above retains the exact member.
                reportAtAlternativeLocation(finding, reportedLocations);
            }
            else {
                report(finding);
            }
        }
    }

    private void reportAtAlternativeLocation(
            Finding finding,
            Set<String> reportedLocations) {
        Element element = finding.element();
        if (reportAtUnusedAnnotation(finding, element, reportedLocations)) {
            return;
        }
        for (Element enclosed : element.getEnclosedElements()) {
            if (reportAtUnusedElement(finding, enclosed, reportedLocations)
                    || reportAtUnusedAnnotation(finding, enclosed, reportedLocations)) {
                return;
            }
        }

        Element enclosing = element.getEnclosingElement();
        while (enclosing != null) {
            if ("PACKAGE".equals(enclosing.getKind().name())
                    || "MODULE".equals(enclosing.getKind().name())) {
                break;
            }
            if (reportAtUnusedElement(finding, enclosing, reportedLocations)
                    || reportAtUnusedAnnotation(finding, enclosing, reportedLocations)) {
                return;
            }
            for (Element sibling : enclosing.getEnclosedElements()) {
                if (!sibling.equals(element)
                        && (reportAtUnusedElement(finding, sibling, reportedLocations)
                        || reportAtUnusedAnnotation(finding, sibling, reportedLocations))) {
                    return;
                }
            }
            enclosing = enclosing.getEnclosingElement();
        }

        // A package-less synthetic compiler element can exhaust the finite source anchors. Old
        // javac may suppress this duplicate, while newer compilers retain it at the original
        // member; never degrade a model diagnostic into a source-less message.
        report(finding);
    }

    private boolean reportAtUnusedElement(
            Finding finding,
            Element element,
            Set<String> reportedLocations) {
        if ("PACKAGE".equals(element.getKind().name())
                || "MODULE".equals(element.getKind().name())) {
            return false;
        }
        String key = ElementNames.qualifiedMemberName(element) + "\u0000\u0000";
        if (!reportedLocations.add(key)) {
            return false;
        }
        messager.printMessage(
                diagnosticKind(finding),
                finding.formattedMessage(),
                element);
        return true;
    }

    private boolean reportAtUnusedAnnotation(
            Finding finding,
            Element element,
            Set<String> reportedLocations) {
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            String key = ElementNames.qualifiedMemberName(element)
                    + "\u0000" + annotation + "\u0000";
            if (reportedLocations.add(key)) {
                messager.printMessage(
                        diagnosticKind(finding),
                        finding.formattedMessage(),
                        element,
                        annotation);
                return true;
            }
        }
        return false;
    }

    private Diagnostic.Kind diagnosticKind(Finding finding) {
        if (finding.severity() == Finding.Severity.WARNING) {
            return Diagnostic.Kind.WARNING;
        }
        if (finding.code().isAlwaysError() || mode == ProcessorMode.STRICT) {
            return Diagnostic.Kind.ERROR;
        }
        return Diagnostic.Kind.WARNING;
    }
}
