package io.github.thriftannotationlint.internal.model;


import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;

public final class ThriftFieldData {
    private final AnnotationMirror annotation;
    private final AnnotationValue idSource;
    private final Short id;
    private final boolean legacyId;
    private final String explicitName;
    private final String requiredness;
    private final Boolean recursive;
    private final ThriftAnnotations.IdlAnnotations idlAnnotations;

    private ThriftFieldData(
            AnnotationMirror annotation,
            AnnotationValue idSource,
            Short id,
            boolean legacyId,
            String explicitName,
            String requiredness,
            Boolean recursive,
            ThriftAnnotations.IdlAnnotations idlAnnotations) {
        this.annotation = annotation;
        this.idSource = idSource;
        this.id = id;
        this.legacyId = legacyId;
        this.explicitName = explicitName;
        this.requiredness = requiredness;
        this.recursive = recursive;
        this.idlAnnotations = idlAnnotations;
    }

    public static ThriftFieldData from(Elements elements, Element element) {
        for (ThriftAnnotationDialect dialect : ThriftAnnotationDialect.values()) {
            AnnotationMirror annotation = ThriftAnnotations.find(element, dialect.thriftField());
            if (annotation != null) {
                return from(elements, annotation);
            }
        }
        return empty();
    }

    public static ThriftFieldData from(
            Elements elements,
            Element element,
            ThriftAnnotationDialect dialect) {
        return from(elements, ThriftAnnotations.find(element, dialect.thriftField()));
    }

    public static ThriftFieldData from(Elements elements, AnnotationMirror annotation) {
        if (annotation == null) {
            return empty();
        }

        AnnotationValue configuredId = ThriftAnnotations.explicitValue(annotation, "value");
        Short id = null;
        if (configuredId != null && configuredId.getValue() instanceof Number) {
            short candidate = ((Number) configuredId.getValue()).shortValue();
            if (candidate != ThriftAnnotations.UNSET_FIELD_ID) {
                id = candidate;
            }
        }

        String name = ThriftAnnotations.stringValue(elements, annotation, "name");
        if (name.isEmpty()) {
            name = null;
        }

        String requiredness = ThriftAnnotations.enumValue(elements, annotation, "requiredness");
        if (requiredness == null) {
            requiredness = "UNSPECIFIED";
        }

        String recursiveValue = ThriftAnnotations.enumValue(elements, annotation, "isRecursive");
        Boolean recursive = null;
        if ("TRUE".equals(recursiveValue)) {
            recursive = Boolean.TRUE;
        }
        else if ("FALSE".equals(recursiveValue)) {
            recursive = Boolean.FALSE;
        }

        ThriftAnnotations.IdlAnnotations idl =
                ThriftAnnotations.readIdlAnnotations(elements, annotation, "idlAnnotations");
        if (recursive == null && idl.values().containsKey(ThriftAnnotations.RECURSIVE_IDL_KEY)) {
            recursive = Boolean.valueOf(
                    "true".equalsIgnoreCase(idl.values().get(ThriftAnnotations.RECURSIVE_IDL_KEY)));
        }

        return new ThriftFieldData(
                annotation,
                configuredId,
                id,
                ThriftAnnotations.booleanValue(elements, annotation, "isLegacyId"),
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
                ThriftAnnotations.IdlAnnotations.empty());
    }

    public AnnotationMirror annotation() {
        return annotation;
    }

    public AnnotationValue idSource() {
        return idSource;
    }

    public Short id() {
        return id;
    }

    public boolean legacyId() {
        return legacyId;
    }

    public String explicitName() {
        return explicitName;
    }

    public String requiredness() {
        return requiredness;
    }

    public Boolean recursive() {
        return recursive;
    }

    public ThriftAnnotations.IdlAnnotations idlAnnotations() {
        return idlAnnotations;
    }
}
