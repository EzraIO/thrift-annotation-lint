package io.github.thriftannotationlint.internal.types;

import io.github.thriftannotationlint.internal.model.FieldPart;
import io.github.thriftannotationlint.internal.model.SwiftModel;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Round-local fail-closed gate for incomplete javac type graphs. */
public final class IncompleteTypeGate {
    private final Map<String, Boolean> roundCache = new LinkedHashMap<String, Boolean>();
    private final JavaTypeIdentityFormatter identityFormatter = new JavaTypeIdentityFormatter();

    public void beginRound() {
        roundCache.clear();
    }

    public boolean containsErrorType(SwiftModel model) {
        for (FieldPart part : model.fieldParts()) {
            if (containsErrorType(part.javaType())) {
                return true;
            }
        }
        return false;
    }

    public boolean containsErrorType(TypeMirror type) {
        if (type == null || type.getKind() == TypeKind.NONE) {
            return false;
        }
        String key;
        try {
            key = type.getKind() + ":" + identityFormatter.exactJavaTypeIdentity(type);
        }
        catch (RuntimeException incompleteSymbol) {
            return true;
        }
        Boolean cached = roundCache.get(key);
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean result = containsErrorType(type, new LinkedHashSet<String>());
        roundCache.put(key, Boolean.valueOf(result));
        return result;
    }

    private boolean containsErrorType(TypeMirror type, Set<String> visiting) {
        if (type == null) {
            return false;
        }
        if (type.getKind() == TypeKind.ERROR) {
            return true;
        }
        String key;
        try {
            key = type.getKind() + ":" + identityFormatter.exactJavaTypeIdentity(type);
        }
        catch (RuntimeException incompleteSymbol) {
            return true;
        }
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
                Element element = declared.asElement();
                if (element instanceof TypeElement) {
                    TypeElement declaredElement = (TypeElement) element;
                    if (containsErrorType(declaredElement.getSuperclass(), visiting)) {
                        return true;
                    }
                    for (TypeMirror interfaceType : declaredElement.getInterfaces()) {
                        if (containsErrorType(interfaceType, visiting)) {
                            return true;
                        }
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
        catch (RuntimeException incompleteSymbol) {
            return true;
        }
        finally {
            visiting.remove(key);
        }
    }
}
