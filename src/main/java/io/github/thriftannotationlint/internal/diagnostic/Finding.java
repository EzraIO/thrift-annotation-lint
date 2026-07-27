package io.github.thriftannotationlint.internal.diagnostic;

import io.github.thriftannotationlint.internal.model.ElementNames;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;

public final class Finding {
    enum Severity {
        ERROR,
        WARNING
    }

    private final DiagnosticCode code;
    private final Severity severity;
    private final String message;
    private final Element element;
    private final AnnotationMirror annotation;
    private final AnnotationValue annotationValue;
    private final String ownerIdentity;
    private final String semanticDeduplicationKey;

    private Finding(
            DiagnosticCode code,
            Severity severity,
            String message,
            Element element,
            AnnotationMirror annotation,
            AnnotationValue annotationValue,
            String ownerIdentity,
            String semanticDeduplicationKey) {
        this.code = code;
        this.severity = severity;
        this.message = message;
        this.element = element;
        this.annotation = annotation;
        this.annotationValue = annotationValue;
        this.ownerIdentity = ownerIdentity;
        this.semanticDeduplicationKey = semanticDeduplicationKey;
    }

    public static Finding error(DiagnosticCode code, Element element, String message) {
        return new Finding(code, Severity.ERROR, message, element, null, null, null, null);
    }

    public static Finding error(
            DiagnosticCode code,
            Element element,
            AnnotationMirror annotation,
            AnnotationValue annotationValue,
            String message) {
        return new Finding(
                code, Severity.ERROR, message, element, annotation, annotationValue, null, null);
    }

    public static Finding warning(DiagnosticCode code, Element element, String message) {
        return new Finding(code, Severity.WARNING, message, element, null, null, null, null);
    }

    public Finding relocated(Element location, String prefix) {
        return new Finding(
                code,
                severity,
                prefix + message,
                location,
                null,
                null,
                ownerIdentity,
                semanticDeduplicationKey);
    }

    public Finding withOwnerIdentity(String identity) {
        return new Finding(
                code,
                severity,
                message,
                element,
                annotation,
                annotationValue,
                identity,
                semanticDeduplicationKey);
    }

    public Finding withSemanticDeduplicationKey(String key) {
        return new Finding(
                code,
                severity,
                message,
                element,
                annotation,
                annotationValue,
                ownerIdentity,
                key);
    }

    public DiagnosticCode code() {
        return code;
    }

    Severity severity() {
        return severity;
    }

    String formattedMessage() {
        return "[" + code.id() + "] " + message;
    }

    public Element element() {
        return element;
    }

    public AnnotationMirror annotation() {
        return annotation;
    }

    AnnotationValue annotationValue() {
        return annotationValue;
    }

    public String ownerIdentity() {
        return ownerIdentity;
    }

    public String semanticDeduplicationKey() {
        return semanticDeduplicationKey;
    }

    public String sortKey() {
        String elementKey = element == null
                ? ""
                : ElementNames.qualifiedMemberName(element);
        return elementKey + "\u0000" + code.id() + "\u0000"
                + (ownerIdentity == null ? "" : ownerIdentity) + "\u0000" + message;
    }

    String locationKey() {
        if (element == null) {
            return null;
        }
        return ElementNames.qualifiedMemberName(element) + "\u0000"
                + (annotation == null ? "" : annotation.toString()) + "\u0000"
                + (annotationValue == null ? "" : annotationValue.toString());
    }
}
