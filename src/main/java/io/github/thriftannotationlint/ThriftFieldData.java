package io.github.thriftannotationlint;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;

final class ThriftFieldData {
    private final AnnotationMirror annotation;
    private final AnnotationValue idSource;
    private final Short id;
    private final boolean legacyId;
    private final String explicitName;
    private final String requiredness;
    private final Boolean recursive;
    private final SwiftAnnotations.IdlAnnotations idlAnnotations;

    private ThriftFieldData(
            AnnotationMirror annotation,
            AnnotationValue idSource,
            Short id,
            boolean legacyId,
            String explicitName,
            String requiredness,
            Boolean recursive,
            SwiftAnnotations.IdlAnnotations idlAnnotations) {
        this.annotation = annotation;
        this.idSource = idSource;
        this.id = id;
        this.legacyId = legacyId;
        this.explicitName = explicitName;
        this.requiredness = requiredness;
        this.recursive = recursive;
        this.idlAnnotations = idlAnnotations;
    }

    static ThriftFieldData from(Elements elements, Element element) {
        return from(elements, SwiftAnnotations.find(element, SwiftAnnotations.THRIFT_FIELD));
    }

    static ThriftFieldData from(Elements elements, AnnotationMirror annotation) {
        if (annotation == null) {
            return empty();
        }

        AnnotationValue configuredId = SwiftAnnotations.explicitValue(annotation, "value");
        Short id = null;
        if (configuredId != null && configuredId.getValue() instanceof Number) {
            short candidate = ((Number) configuredId.getValue()).shortValue();
            if (candidate != SwiftAnnotations.UNSET_FIELD_ID) {
                id = candidate;
            }
        }

        String name = SwiftAnnotations.stringValue(elements, annotation, "name");
        if (name.isEmpty()) {
            name = null;
        }

        String requiredness = SwiftAnnotations.enumValue(elements, annotation, "requiredness");
        if (requiredness == null) {
            requiredness = "UNSPECIFIED";
        }

        String recursiveValue = SwiftAnnotations.enumValue(elements, annotation, "isRecursive");
        Boolean recursive = null;
        if ("TRUE".equals(recursiveValue)) {
            recursive = Boolean.TRUE;
        }
        else if ("FALSE".equals(recursiveValue)) {
            recursive = Boolean.FALSE;
        }

        SwiftAnnotations.IdlAnnotations idl =
                SwiftAnnotations.readIdlAnnotations(elements, annotation, "idlAnnotations");
        if (recursive == null && idl.values().containsKey(SwiftAnnotations.RECURSIVE_IDL_KEY)) {
            recursive = Boolean.valueOf(
                    "true".equalsIgnoreCase(idl.values().get(SwiftAnnotations.RECURSIVE_IDL_KEY)));
        }

        return new ThriftFieldData(
                annotation,
                configuredId,
                id,
                SwiftAnnotations.booleanValue(elements, annotation, "isLegacyId"),
                name,
                requiredness,
                recursive,
                idl);
    }

    static ThriftFieldData empty() {
        return new ThriftFieldData(
                null,
                null,
                null,
                false,
                null,
                "UNSPECIFIED",
                null,
                SwiftAnnotations.IdlAnnotations.empty());
    }

    AnnotationMirror annotation() {
        return annotation;
    }

    AnnotationValue idSource() {
        return idSource;
    }

    Short id() {
        return id;
    }

    boolean legacyId() {
        return legacyId;
    }

    String explicitName() {
        return explicitName;
    }

    String requiredness() {
        return requiredness;
    }

    Boolean recursive() {
        return recursive;
    }

    SwiftAnnotations.IdlAnnotations idlAnnotations() {
        return idlAnnotations;
    }
}
