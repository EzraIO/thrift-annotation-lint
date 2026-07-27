package io.github.thriftannotationlint.internal.extract;

import io.github.thriftannotationlint.internal.model.ThriftAnnotations;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;

import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.model.ElementNames;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Extracts the reflection-visible @ThriftEnumValue method for an enum. */
final class SwiftEnumMetadataExtractor {
    private final Elements elements;
    private final Types types;
    private final SwiftMemberResolver memberResolver;

    SwiftEnumMetadataExtractor(
            Elements elements,
            Types types,
            SwiftMemberResolver memberResolver) {
        this.elements = elements;
        this.types = types;
        this.memberResolver = memberResolver;
    }

    List<ExecutableElement> extract(
            TypeElement enumType,
            ThriftAnnotationDialect dialect,
            List<Finding> findings) {
        List<ExecutableElement> methods = new ArrayList<ExecutableElement>();
        for (ExecutableElement method
                : ElementFilter.methodsIn(elements.getAllMembers(enumType))) {
            // Class.getMethods exposes the override, so an unannotated override hides an
            // annotated interface declaration while an inherited default remains visible.
            if (ThriftAnnotations.has(method, dialect.thriftEnumValue())) {
                methods.add(method);
            }
        }
        Collections.sort(methods, new Comparator<ExecutableElement>() {
            @Override
            public int compare(ExecutableElement left, ExecutableElement right) {
                return ElementNames.qualifiedMemberName(left)
                        .compareTo(ElementNames.qualifiedMemberName(right));
            }
        });
        if (methods.size() > 1) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_ENUM_VALUE_METHOD,
                    methods.get(1),
                    "Enum '" + enumType.getQualifiedName()
                            + "' must declare at most one @ThriftEnumValue method."));
        }
        for (ExecutableElement method : methods) {
            // Runtime metadata validates the reflected declaration. Generic substitution at the
            // enum use site does not change its erased JVM return type.
            ExecutableType declared = (ExecutableType) method.asType();
            TypeMirror erasedReturnType = types.erasure(declared.getReturnType());
            if (!memberResolver.isPublicInstance(method)
                    || !declared.getParameterTypes().isEmpty()
                    || !method.getTypeParameters().isEmpty()
                    || !isIntOrInteger(erasedReturnType)
                    || hasInvalidEnumValueBridge(enumType, method, erasedReturnType)) {
                findings.add(Finding.error(
                        DiagnosticCode.INVALID_ENUM_VALUE_METHOD,
                        method,
                        "@ThriftEnumValue method '" + method.getSimpleName()
                                + "' must be public, non-static, declared by a public type, "
                                + "non-generic, take no arguments, and return int or Integer."));
            }
        }
        validateUnknownValue(enumType, dialect, findings);
        return methods;
    }

    private void validateUnknownValue(
            TypeElement enumType,
            ThriftAnnotationDialect dialect,
            List<Finding> findings) {
        String annotationName = dialect.thriftEnumUnknownValue();
        if (annotationName == null) {
            return;
        }
        List<VariableElement> unknownValues = new ArrayList<VariableElement>();
        for (VariableElement constant : ElementFilter.fieldsIn(enumType.getEnclosedElements())) {
            if (constant.getKind() == javax.lang.model.element.ElementKind.ENUM_CONSTANT
                    && ThriftAnnotations.has(constant, annotationName)) {
                unknownValues.add(constant);
            }
        }
        if (unknownValues.size() > 1) {
            VariableElement target = unknownValues.get(1);
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_ENUM_UNKNOWN_VALUE,
                    target,
                    ThriftAnnotations.find(target, annotationName),
                    null,
                    "Drift enum '" + enumType.getQualifiedName()
                            + "' must declare at most one @ThriftEnumUnknownValue constant."));
        }
    }

    private boolean hasInvalidEnumValueBridge(
            TypeElement enumType,
            ExecutableElement method,
            TypeMirror erasedReturnType) {
        for (TypeElement hierarchyType : memberResolver.hierarchy(enumType)) {
            for (ExecutableElement inherited
                    : ElementFilter.methodsIn(hierarchyType.getEnclosedElements())) {
                if (method.equals(inherited)
                        || !method.getSimpleName().contentEquals(inherited.getSimpleName())) {
                    continue;
                }
                try {
                    if (elements.overrides(method, inherited, enumType)) {
                        TypeMirror bridgeReturnType = types.erasure(inherited.getReturnType());
                        if (!types.isSameType(erasedReturnType, bridgeReturnType)
                                && !isIntOrInteger(bridgeReturnType)) {
                            return true;
                        }
                    }
                }
                catch (IllegalArgumentException ignored) {
                    // The unresolved-symbol gate retries incomplete hierarchies in a later round.
                }
            }
        }
        return false;
    }

    private boolean isIntOrInteger(TypeMirror type) {
        if (type.getKind() == TypeKind.INT) {
            return true;
        }
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        Element element = ((DeclaredType) type).asElement();
        return element instanceof TypeElement
                && "java.lang.Integer".contentEquals(
                ((TypeElement) element).getQualifiedName());
    }
}
