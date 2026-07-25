package io.github.thriftannotationlint;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SwiftModel {
    enum Kind {
        STRUCT,
        UNION,
        ENUM
    }

    private final Kind kind;
    private final TypeElement type;
    private final DeclaredType declaredType;
    private final String identity;
    private final TypeElement builder;
    private final List<FieldPart> fieldParts;
    private final List<ExecutableElement> constructionExecutables;
    private final List<ElementWithAnnotation> unionIds;
    private final List<ExecutableElement> enumValueMethods;

    SwiftModel(
            Kind kind,
            TypeElement type,
            DeclaredType declaredType,
            String identity,
            TypeElement builder,
            List<FieldPart> fieldParts,
            List<ExecutableElement> constructionExecutables,
            List<ElementWithAnnotation> unionIds,
            List<ExecutableElement> enumValueMethods) {
        this.kind = kind;
        this.type = type;
        this.declaredType = declaredType;
        this.identity = identity;
        this.builder = builder;
        this.fieldParts = immutableCopy(fieldParts);
        this.constructionExecutables = immutableCopy(constructionExecutables);
        this.unionIds = immutableCopy(unionIds);
        this.enumValueMethods = immutableCopy(enumValueMethods);
    }

    Kind kind() {
        return kind;
    }

    TypeElement type() {
        return type;
    }

    DeclaredType declaredType() {
        return declaredType;
    }

    String identity() {
        return identity;
    }

    TypeElement builder() {
        return builder;
    }

    List<FieldPart> fieldParts() {
        return fieldParts;
    }

    List<ExecutableElement> constructionExecutables() {
        return constructionExecutables;
    }

    List<ElementWithAnnotation> unionIds() {
        return unionIds;
    }

    List<ExecutableElement> enumValueMethods() {
        return enumValueMethods;
    }

    String displayName() {
        return type.getQualifiedName().toString();
    }

    private static <T> List<T> immutableCopy(List<T> input) {
        return Collections.unmodifiableList(new ArrayList<T>(input));
    }

    static final class ElementWithAnnotation {
        private final javax.lang.model.element.Element element;
        private final javax.lang.model.element.AnnotationMirror annotation;

        ElementWithAnnotation(
                javax.lang.model.element.Element element,
                javax.lang.model.element.AnnotationMirror annotation) {
            this.element = element;
            this.annotation = annotation;
        }

        javax.lang.model.element.Element element() {
            return element;
        }

        javax.lang.model.element.AnnotationMirror annotation() {
            return annotation;
        }
    }
}
