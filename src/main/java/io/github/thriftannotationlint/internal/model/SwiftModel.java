package io.github.thriftannotationlint.internal.model;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SwiftModel {
    public enum Kind {
        STRUCT,
        UNION,
        ENUM
    }

    private final Kind kind;
    private final TypeElement type;
    private final DeclaredType declaredType;
    private final String identity;
    private final String cacheKey;
    private final ThriftAnnotationDialect dialect;
    private final TypeElement builder;
    private final List<FieldPart> fieldParts;
    private final List<ExecutableElement> constructionExecutables;
    private final List<ElementWithAnnotation> unionIds;
    private final List<ExecutableElement> enumValueMethods;

    public SwiftModel(
            Kind kind,
            TypeElement type,
            DeclaredType declaredType,
            String identity,
            String cacheKey,
            ThriftAnnotationDialect dialect,
            TypeElement builder,
            List<FieldPart> fieldParts,
            List<ExecutableElement> constructionExecutables,
            List<ElementWithAnnotation> unionIds,
            List<ExecutableElement> enumValueMethods) {
        this.kind = kind;
        this.type = type;
        this.declaredType = declaredType;
        this.identity = identity;
        this.cacheKey = cacheKey;
        this.dialect = dialect;
        this.builder = builder;
        this.fieldParts = immutableCopy(fieldParts);
        this.constructionExecutables = immutableCopy(constructionExecutables);
        this.unionIds = immutableCopy(unionIds);
        this.enumValueMethods = immutableCopy(enumValueMethods);
    }

    public Kind kind() {
        return kind;
    }

    public TypeElement type() {
        return type;
    }

    public DeclaredType declaredType() {
        return declaredType;
    }

    public String identity() {
        return identity;
    }

    public String cacheKey() {
        return cacheKey;
    }

    public ThriftAnnotationDialect dialect() {
        return dialect;
    }

    public TypeElement builder() {
        return builder;
    }

    public List<FieldPart> fieldParts() {
        return fieldParts;
    }

    public List<ExecutableElement> constructionExecutables() {
        return constructionExecutables;
    }

    public List<ElementWithAnnotation> unionIds() {
        return unionIds;
    }

    List<ExecutableElement> enumValueMethods() {
        return enumValueMethods;
    }

    public String displayName() {
        return type.getQualifiedName().toString();
    }

    private static <T> List<T> immutableCopy(List<T> input) {
        return Collections.unmodifiableList(new ArrayList<T>(input));
    }

    public static final class ElementWithAnnotation {
        private final javax.lang.model.element.Element element;
        private final javax.lang.model.element.AnnotationMirror annotation;

        public ElementWithAnnotation(
                javax.lang.model.element.Element element,
                javax.lang.model.element.AnnotationMirror annotation) {
            this.element = element;
            this.annotation = annotation;
        }

        public javax.lang.model.element.Element element() {
            return element;
        }

        public javax.lang.model.element.AnnotationMirror annotation() {
            return annotation;
        }
    }
}
