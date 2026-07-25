package io.github.thriftannotationlint;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.LinkedHashSet;
import java.util.Set;

/** Detects transient javac error types without retaining mirrors across processing rounds. */
final class UnresolvedSymbolInspector {
    private final Elements elements;
    private final Types types;

    UnresolvedSymbolInspector(Elements elements, Types types) {
        this.elements = elements;
        this.types = types;
    }

    boolean hasUnresolvedSymbols(
            TypeElement type,
            DeclaredType declaredType,
            SwiftModel.Kind kind) {
        if (containsErrorType(declaredType, new LinkedHashSet<String>())
                || hasUnresolvedHierarchy(type, new LinkedHashSet<String>())) {
            return true;
        }
        if (kind != SwiftModel.Kind.ENUM) {
            AnnotationMirror modelAnnotation = SwiftAnnotations.find(
                    type,
                    kind == SwiftModel.Kind.STRUCT
                            ? SwiftAnnotations.THRIFT_STRUCT
                            : SwiftAnnotations.THRIFT_UNION);
            if (modelAnnotation != null) {
                TypeMirror builder = SwiftAnnotations.classValue(
                        elements, modelAnnotation, "builder");
                if (builder != null && builder.getKind() == TypeKind.ERROR) {
                    return true;
                }
                Element builderElement = builder == null ? null : types.asElement(builder);
                if (builderElement instanceof TypeElement
                        && !"void".equals(builder.toString())
                        && !"java.lang.Void".equals(builder.toString())
                        && hasUnresolvedHierarchy(
                        (TypeElement) builderElement,
                        new LinkedHashSet<String>())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasUnresolvedHierarchy(TypeElement type, Set<String> visited) {
        String name = type.getQualifiedName().toString();
        if ("java.lang.Object".equals(name) || !visited.add(name)) {
            return false;
        }
        for (Element enclosed : type.getEnclosedElements()) {
            if (!(enclosed instanceof TypeElement)
                    && containsErrorType(enclosed.asType(), new LinkedHashSet<String>())) {
                return true;
            }
        }
        TypeMirror superclass = type.getSuperclass();
        if (containsErrorType(superclass, new LinkedHashSet<String>())) {
            return true;
        }
        if (superclass.getKind() == TypeKind.DECLARED) {
            Element superElement = ((DeclaredType) superclass).asElement();
            if (superElement instanceof TypeElement
                    && hasUnresolvedHierarchy((TypeElement) superElement, visited)) {
                return true;
            }
        }
        for (TypeMirror interfaceType : type.getInterfaces()) {
            if (containsErrorType(interfaceType, new LinkedHashSet<String>())) {
                return true;
            }
            if (interfaceType.getKind() == TypeKind.DECLARED) {
                Element interfaceElement = ((DeclaredType) interfaceType).asElement();
                if (interfaceElement instanceof TypeElement
                        && hasUnresolvedHierarchy((TypeElement) interfaceElement, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsErrorType(TypeMirror type, Set<String> visiting) {
        if (type == null || type.getKind() == TypeKind.NONE) {
            return false;
        }
        if (type.getKind() == TypeKind.ERROR) {
            return true;
        }
        String key = type.getKind() + ":" + type;
        if (!visiting.add(key)) {
            return false;
        }
        try {
            if (type.getKind() == TypeKind.DECLARED) {
                DeclaredType declared = (DeclaredType) type;
                if (containsErrorType(declared.getEnclosingType(), visiting)) {
                    return true;
                }
                for (TypeMirror argument : declared.getTypeArguments()) {
                    if (containsErrorType(argument, visiting)) {
                        return true;
                    }
                }
            }
            else if (type.getKind() == TypeKind.EXECUTABLE) {
                ExecutableType executable = (ExecutableType) type;
                if (containsErrorType(executable.getReturnType(), visiting)) {
                    return true;
                }
                for (TypeMirror parameter : executable.getParameterTypes()) {
                    if (containsErrorType(parameter, visiting)) {
                        return true;
                    }
                }
                for (TypeMirror thrown : executable.getThrownTypes()) {
                    if (containsErrorType(thrown, visiting)) {
                        return true;
                    }
                }
            }
            else if (type.getKind() == TypeKind.ARRAY) {
                return containsErrorType(((ArrayType) type).getComponentType(), visiting);
            }
            else if (type.getKind() == TypeKind.TYPEVAR) {
                return containsErrorType(((TypeVariable) type).getUpperBound(), visiting);
            }
            else if (type.getKind() == TypeKind.WILDCARD) {
                WildcardType wildcard = (WildcardType) type;
                return containsErrorType(wildcard.getExtendsBound(), visiting)
                        || containsErrorType(wildcard.getSuperBound(), visiting);
            }
            else if (type.getKind() == TypeKind.INTERSECTION) {
                for (TypeMirror bound : ((IntersectionType) type).getBounds()) {
                    if (containsErrorType(bound, visiting)) {
                        return true;
                    }
                }
            }
            return false;
        }
        finally {
            visiting.remove(key);
        }
    }
}
