package io.github.thriftannotationlint.internal.model;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.SimpleAnnotationValueVisitor8;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ThriftAnnotations {
    static final short UNSET_FIELD_ID = Short.MIN_VALUE;
    static final String RECURSIVE_IDL_KEY = "swift.recursive_reference";

    private ThriftAnnotations() {
    }

    public static ThriftAnnotationDialect dialectFor(
            TypeElement type,
            SwiftModel.Kind kind) {
        for (ThriftAnnotationDialect dialect : ThriftAnnotationDialect.values()) {
            if (has(type, dialect.modelAnnotation(kind))) {
                return dialect;
            }
        }
        return null;
    }

    public static int modelAnnotationCount(TypeElement type) {
        int count = 0;
        for (ThriftAnnotationDialect dialect : ThriftAnnotationDialect.values()) {
            count += has(type, dialect.thriftStruct()) ? 1 : 0;
            count += has(type, dialect.thriftUnion()) ? 1 : 0;
            count += has(type, dialect.thriftEnum()) ? 1 : 0;
        }
        return count;
    }

    public static boolean isSupportedAnnotation(String annotationName) {
        for (ThriftAnnotationDialect dialect : ThriftAnnotationDialect.values()) {
            if (dialect.thriftStruct().equals(annotationName)
                    || dialect.thriftField().equals(annotationName)
                    || dialect.thriftConstructor().equals(annotationName)
                    || dialect.thriftUnion().equals(annotationName)
                    || dialect.thriftUnionId().equals(annotationName)
                    || dialect.thriftEnum().equals(annotationName)
                    || dialect.thriftEnumValue().equals(annotationName)
                    || annotationName.equals(dialect.thriftEnumUnknownValue())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isThriftFieldAnnotation(String annotationName) {
        for (ThriftAnnotationDialect dialect : ThriftAnnotationDialect.values()) {
            if (dialect.thriftField().equals(annotationName)) {
                return true;
            }
        }
        return false;
    }

    public static AnnotationMirror find(Element element, String annotationName) {
        if (element == null) {
            return null;
        }
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            Element annotationElement = annotation.getAnnotationType().asElement();
            if (annotationElement instanceof TypeElement
                    && annotationName.contentEquals(((TypeElement) annotationElement).getQualifiedName())) {
                return annotation;
            }
        }
        return null;
    }

    public static boolean has(Element element, String annotationName) {
        return find(element, annotationName) != null;
    }

    public static AnnotationValue explicitValue(AnnotationMirror annotation, String name) {
        if (annotation == null) {
            return null;
        }
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                : annotation.getElementValues().entrySet()) {
            if (name.contentEquals(entry.getKey().getSimpleName())) {
                return entry.getValue();
            }
        }
        return null;
    }

    static AnnotationValue valueWithDefault(Elements elements, AnnotationMirror annotation, String name) {
        if (annotation == null) {
            return null;
        }
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                : elements.getElementValuesWithDefaults(annotation).entrySet()) {
            if (name.contentEquals(entry.getKey().getSimpleName())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static String stringValue(Elements elements, AnnotationMirror annotation, String name) {
        AnnotationValue value = explicitOrDefaultValue(elements, annotation, name);
        return stringValue(value);
    }

    public static String stringValue(AnnotationValue value) {
        if (value == null) {
            return "";
        }
        String result = value.accept(
                new SimpleAnnotationValueVisitor8<String, Void>(null) {
                    @Override
                    public String visitString(String text, Void ignored) {
                        return text;
                    }
                },
                null);
        return result == null ? "" : result;
    }

    static boolean booleanValue(Elements elements, AnnotationMirror annotation, String name) {
        AnnotationValue value = explicitOrDefaultValue(elements, annotation, name);
        return value != null && Boolean.TRUE.equals(value.getValue());
    }

    static String enumValue(Elements elements, AnnotationMirror annotation, String name) {
        AnnotationValue value = explicitOrDefaultValue(elements, annotation, name);
        if (value == null) {
            return null;
        }
        return value.accept(
                new SimpleAnnotationValueVisitor8<String, Void>(null) {
                    @Override
                    public String visitEnumConstant(
                            VariableElement constant,
                            Void ignored) {
                        return constant.getSimpleName().toString();
                    }
                },
                null);
    }

    public static TypeMirror classValue(Elements elements, AnnotationMirror annotation, String name) {
        AnnotationValue value = explicitOrDefaultValue(elements, annotation, name);
        if (value == null || !(value.getValue() instanceof TypeMirror)) {
            return null;
        }
        return (TypeMirror) value.getValue();
    }

    public static boolean isVoidClassValue(TypeMirror value) {
        return value == null || value.getKind() == TypeKind.VOID;
    }

    private static AnnotationValue explicitOrDefaultValue(
            Elements elements,
            AnnotationMirror annotation,
            String name) {
        AnnotationValue explicit = explicitValue(annotation, name);
        return explicit == null ? valueWithDefault(elements, annotation, name) : explicit;
    }

    public static IdlAnnotations readIdlAnnotations(
            Elements elements,
            AnnotationMirror owner,
            String memberName) {
        AnnotationValue arrayValue = valueWithDefault(elements, owner, memberName);
        if (arrayValue == null) {
            return IdlAnnotations.empty();
        }

        Map<String, String> values = new LinkedHashMap<String, String>();
        List<String> duplicateKeys = new ArrayList<String>();
        List<AnnotationMirror> entries = nestedAnnotations(arrayValue);
        for (AnnotationMirror annotation : entries) {
            String key = stringValue(elements, annotation, "key");
            String value = stringValue(elements, annotation, "value");
            if (values.containsKey(key)) {
                duplicateKeys.add(key);
            }
            else {
                values.put(key, value);
            }
        }
        return new IdlAnnotations(values, duplicateKeys, arrayValue);
    }

    private static List<AnnotationMirror> nestedAnnotations(AnnotationValue value) {
        final List<AnnotationMirror> result = new ArrayList<AnnotationMirror>();
        if (value != null) {
            value.accept(new SimpleAnnotationValueVisitor8<Void, List<AnnotationMirror>>() {
                @Override
                public Void visitAnnotation(
                        AnnotationMirror annotation,
                        List<AnnotationMirror> target) {
                    target.add(annotation);
                    return null;
                }

                @Override
                public Void visitArray(
                        List<? extends AnnotationValue> values,
                        List<AnnotationMirror> target) {
                    for (AnnotationValue nested : values) {
                        nested.accept(this, target);
                    }
                    return null;
                }
            }, result);
        }
        return result;
    }

    public static final class IdlAnnotations {
        private final Map<String, String> values;
        private final List<String> duplicateKeys;
        private final AnnotationValue sourceValue;

        private IdlAnnotations(
                Map<String, String> values,
                List<String> duplicateKeys,
                AnnotationValue sourceValue) {
            this.values = Collections.unmodifiableMap(new LinkedHashMap<String, String>(values));
            this.duplicateKeys = Collections.unmodifiableList(new ArrayList<String>(duplicateKeys));
            this.sourceValue = sourceValue;
        }

        static IdlAnnotations empty() {
            return new IdlAnnotations(
                    Collections.<String, String>emptyMap(),
                    Collections.<String>emptyList(),
                    null);
        }

        public Map<String, String> values() {
            return values;
        }

        public List<String> duplicateKeys() {
            return duplicateKeys;
        }

        public AnnotationValue sourceValue() {
            return sourceValue;
        }
    }
}
