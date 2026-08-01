package io.github.thriftannotationlint.internal.extract;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.List;

/** Resolves executable use-site types while retaining method-owned type variables. */
final class ExecutableTypeResolver {
    private final Types types;

    ExecutableTypeResolver(Types types) {
        this.types = types;
    }

    SwiftMemberResolver.ResolvedExecutable resolve(
            DeclaredType root,
            ExecutableElement method) {
        ExecutableType declared = (ExecutableType) method.asType();
        TypeMirror resolved = resolveMemberType(root, method);
        ExecutableType executable = resolved instanceof ExecutableType
                ? (ExecutableType) resolved
                : declared;
        List<TypeMirror> parameterTypes = resolveParameters(method, declared, executable);
        return new SwiftMemberResolver.ResolvedExecutable(
                restore(method, declared.getReturnType(), executable.getReturnType()),
                parameterTypes);
    }

    TypeMirror resolveMemberType(DeclaredType root, Element member) {
        try {
            return types.asMemberOf(root, member);
        }
        catch (IllegalArgumentException ignored) {
            return member.asType();
        }
    }

    private List<TypeMirror> resolveParameters(
            ExecutableElement method,
            ExecutableType declared,
            ExecutableType resolved) {
        List<TypeMirror> result = new ArrayList<TypeMirror>();
        List<? extends TypeMirror> declaredParameters = declared.getParameterTypes();
        List<? extends TypeMirror> resolvedParameters = resolved.getParameterTypes();
        for (int index = 0; index < resolvedParameters.size(); index++) {
            TypeMirror declaredParameter = index < declaredParameters.size()
                    ? declaredParameters.get(index)
                    : resolvedParameters.get(index);
            result.add(restore(method, declaredParameter, resolvedParameters.get(index)));
        }
        return result;
    }

    private TypeMirror restore(
            ExecutableElement method,
            TypeMirror declared,
            TypeMirror resolved) {
        if (declared == null || resolved == null) {
            return resolved;
        }
        if (declared.getKind() == TypeKind.TYPEVAR
                && isTypeParameterOf(method, (TypeVariable) declared)) {
            return declared;
        }
        if (declared.getKind() == TypeKind.ARRAY && resolved.getKind() == TypeKind.ARRAY) {
            return types.getArrayType(restore(
                    method,
                    ((ArrayType) declared).getComponentType(),
                    ((ArrayType) resolved).getComponentType()));
        }
        if (declared.getKind() == TypeKind.WILDCARD
                && resolved.getKind() == TypeKind.WILDCARD) {
            return restoreWildcard(method, (WildcardType) declared, (WildcardType) resolved);
        }
        if (declared.getKind() != TypeKind.DECLARED
                || resolved.getKind() != TypeKind.DECLARED) {
            return resolved;
        }
        return restoreDeclared(method, (DeclaredType) declared, (DeclaredType) resolved);
    }

    private TypeMirror restoreWildcard(
            ExecutableElement method,
            WildcardType declared,
            WildcardType resolved) {
        TypeMirror extendsBound = declared.getExtendsBound() == null
                ? null
                : restore(method, declared.getExtendsBound(), resolved.getExtendsBound());
        TypeMirror superBound = declared.getSuperBound() == null
                ? null
                : restore(method, declared.getSuperBound(), resolved.getSuperBound());
        return types.getWildcardType(extendsBound, superBound);
    }

    private TypeMirror restoreDeclared(
            ExecutableElement method,
            DeclaredType declared,
            DeclaredType resolved) {
        List<? extends TypeMirror> declaredArguments = declared.getTypeArguments();
        List<? extends TypeMirror> resolvedArguments = resolved.getTypeArguments();
        if (declaredArguments.size() != resolvedArguments.size()) {
            return resolved;
        }
        TypeMirror[] arguments = restoredArguments(
                method, declaredArguments, resolvedArguments);
        Element element = resolved.asElement();
        if (!(element instanceof TypeElement)) {
            return resolved;
        }
        TypeMirror enclosing = restoredEnclosing(method, declared, resolved);
        if (enclosing != null && enclosing.getKind() == TypeKind.DECLARED) {
            try {
                return types.getDeclaredType(
                        (DeclaredType) enclosing, (TypeElement) element, arguments);
            }
            catch (IllegalArgumentException ignored) {
                // Static nested types are reconstructed without an enclosing type below.
            }
        }
        try {
            return types.getDeclaredType((TypeElement) element, arguments);
        }
        catch (IllegalArgumentException ignored) {
            return resolved;
        }
    }

    private TypeMirror[] restoredArguments(
            ExecutableElement method,
            List<? extends TypeMirror> declared,
            List<? extends TypeMirror> resolved) {
        TypeMirror[] arguments = new TypeMirror[resolved.size()];
        for (int index = 0; index < arguments.length; index++) {
            arguments[index] = restore(method, declared.get(index), resolved.get(index));
        }
        return arguments;
    }

    private TypeMirror restoredEnclosing(
            ExecutableElement method,
            DeclaredType declared,
            DeclaredType resolved) {
        TypeMirror restored = resolved.getEnclosingType();
        TypeMirror declaredEnclosing = declared.getEnclosingType();
        if (declaredEnclosing != null
                && restored != null
                && declaredEnclosing.getKind() == TypeKind.DECLARED
                && restored.getKind() == TypeKind.DECLARED) {
            return restore(method, declaredEnclosing, restored);
        }
        return restored;
    }

    private boolean isTypeParameterOf(ExecutableElement executable, TypeVariable type) {
        Element parameter = type.asElement();
        for (TypeParameterElement declared : executable.getTypeParameters()) {
            if (declared.equals(parameter)) {
                return true;
            }
        }
        return false;
    }
}
