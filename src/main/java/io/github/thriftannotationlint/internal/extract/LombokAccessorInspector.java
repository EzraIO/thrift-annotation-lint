package io.github.thriftannotationlint.internal.extract;

import io.github.thriftannotationlint.internal.model.ThriftAnnotations;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

/** Recognizes source-level Lombok declarations without adding a production Lombok dependency. */
final class LombokAccessorInspector {
    private static final String DATA = "lombok.Data";
    private static final String VALUE = "lombok.Value";
    private static final String GETTER = "lombok.Getter";
    private static final String SETTER = "lombok.Setter";

    boolean mayGenerateAccessors(TypeElement declaringType, VariableElement field) {
        return hasAccessorAnnotation(field)
                || ThriftAnnotations.has(declaringType, DATA)
                || ThriftAnnotations.has(declaringType, VALUE)
                || ThriftAnnotations.has(declaringType, GETTER)
                || ThriftAnnotations.has(declaringType, SETTER);
    }

    private boolean hasAccessorAnnotation(VariableElement field) {
        return ThriftAnnotations.has(field, GETTER)
                || ThriftAnnotations.has(field, SETTER);
    }
}
