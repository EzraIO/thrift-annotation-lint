package io.github.thriftannotationlint.internal.types;

import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves instantiated supertypes without relying on compiler-internal APIs. */
final class TypeHierarchyResolver {
    private final Types types;

    TypeHierarchyResolver(Types types) {
        this.types = types;
    }

    DeclaredType asSupertype(DeclaredType candidate, TypeElement target) {
        DeclaredType match = asSupertypeFromMirrors(
                candidate, target, new HashSet<String>());
        if (match != null) {
            return match;
        }
        try {
            return asSupertypeFromElements(
                    candidate, target, new HashSet<String>());
        }
        catch (RuntimeException incompleteSymbol) {
            // javac 8 can expose half-completed TypeVariableSymbols while another processor is
            // generating their owner. The next round rebuilds historical roots with fresh state.
            return null;
        }
    }

    private DeclaredType asSupertypeFromMirrors(
            DeclaredType candidate,
            TypeElement target,
            Set<String> visiting) {
        if (target == null) {
            return null;
        }
        Element candidateElement = candidate.asElement();
        boolean sameDeclaration = candidateElement instanceof TypeElement
                && ((TypeElement) candidateElement).getQualifiedName()
                .contentEquals(target.getQualifiedName());
        boolean sameErasure = false;
        if (candidate.getKind() == TypeKind.DECLARED) {
            try {
                sameErasure = types.isSameType(
                        types.erasure(candidate), types.erasure(target.asType()));
            }
            catch (RuntimeException incompleteSymbol) {
                // Qualified declaration names above remain stable for incomplete javac symbols.
            }
        }
        if (sameDeclaration || sameErasure) {
            return candidate;
        }
        if (!visiting.add(candidate.toString())) {
            return null;
        }
        try {
            for (TypeMirror supertype : types.directSupertypes(candidate)) {
                if (supertype.getKind() != TypeKind.DECLARED) {
                    continue;
                }
                DeclaredType match = asSupertypeFromMirrors(
                        (DeclaredType) supertype, target, visiting);
                if (match != null) {
                    return match;
                }
            }
        }
        catch (RuntimeException incompleteSymbol) {
            // Fall through to the element hierarchy, which javac often completes first.
        }
        return null;
    }

    private DeclaredType asSupertypeFromElements(
            DeclaredType candidate,
            TypeElement target,
            Set<String> visiting) {
        if (target == null) {
            return null;
        }
        Element element = candidate.asElement();
        if (!(element instanceof TypeElement)) {
            return null;
        }
        TypeElement type = (TypeElement) element;
        if (type.getQualifiedName().contentEquals(target.getQualifiedName())) {
            return candidate;
        }
        String visitKey = type.getQualifiedName() + ":" + candidate;
        if (!visiting.add(visitKey)) {
            return null;
        }

        Map<Element, TypeMirror> substitutions = new LinkedHashMap<Element, TypeMirror>();
        boolean rawView = collectSubstitutions(candidate, substitutions);

        List<TypeMirror> declaredSupertypes = new ArrayList<TypeMirror>();
        TypeMirror superclass = type.getSuperclass();
        if (superclass != null && superclass.getKind() != TypeKind.NONE) {
            declaredSupertypes.add(superclass);
        }
        declaredSupertypes.addAll(type.getInterfaces());
        for (TypeMirror declaredSupertype : declaredSupertypes) {
            TypeMirror resolved = rawView
                    ? types.erasure(declaredSupertype)
                    : substituteType(
                            declaredSupertype,
                            substitutions,
                            new HashSet<String>());
            if (resolved == null
                    || (resolved.getKind() != TypeKind.DECLARED
                    && resolved.getKind() != TypeKind.ERROR)) {
                continue;
            }
            DeclaredType match = asSupertypeFromElements(
                    (DeclaredType) resolved, target, visiting);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    /**
     * Collects use-site arguments from every non-static owner before the member's own arguments.
     * A raw owner makes its non-static member raw as well, matching Types.directSupertypes().
     */
    private boolean collectSubstitutions(
            DeclaredType candidate,
            Map<Element, TypeMirror> substitutions) {
        return collectSubstitutions(
                candidate,
                substitutions,
                new HashSet<Element>());
    }

    private boolean collectSubstitutions(
            DeclaredType candidate,
            Map<Element, TypeMirror> substitutions,
            Set<Element> visitingOwners) {
        Element element = candidate.asElement();
        if (!(element instanceof TypeElement)) {
            return false;
        }
        if (!visitingOwners.add(element)) {
            // javac 8 can expose a self-referential enclosing type while a generated owner is
            // incomplete. Treat that synthetic edge as the end of the owner chain.
            return false;
        }
        TypeElement type = (TypeElement) element;
        boolean rawView = false;
        TypeMirror enclosing = candidate.getEnclosingType();
        if (!type.getModifiers().contains(Modifier.STATIC)
                && enclosing != null
                && (enclosing.getKind() == TypeKind.DECLARED
                || enclosing.getKind() == TypeKind.ERROR)) {
            rawView = collectSubstitutions(
                    (DeclaredType) enclosing,
                    substitutions,
                    visitingOwners);
        }

        List<? extends TypeParameterElement> parameters = type.getTypeParameters();
        List<? extends TypeMirror> arguments = candidate.getTypeArguments();
        if (!parameters.isEmpty() && arguments.isEmpty()) {
            rawView = true;
        }
        for (int index = 0; index < parameters.size() && index < arguments.size(); index++) {
            substitutions.put(parameters.get(index), arguments.get(index));
        }
        return rawView;
    }

    private TypeMirror substituteType(
            TypeMirror type,
            Map<Element, TypeMirror> substitutions,
            Set<String> visiting) {
        if (type == null) {
            return null;
        }
        if (type.getKind() == TypeKind.TYPEVAR) {
            TypeMirror replacement = substitutions.get(((TypeVariable) type).asElement());
            return replacement == null ? type : replacement;
        }
        String visitKey = type.getKind() + ":" + type;
        if (!visiting.add(visitKey)) {
            return type;
        }
        try {
            if (type.getKind() == TypeKind.ARRAY) {
                return types.getArrayType(substituteType(
                        ((ArrayType) type).getComponentType(), substitutions, visiting));
            }
            if (type.getKind() == TypeKind.WILDCARD) {
                WildcardType wildcard = (WildcardType) type;
                return types.getWildcardType(
                        substituteType(wildcard.getExtendsBound(), substitutions, visiting),
                        substituteType(wildcard.getSuperBound(), substitutions, visiting));
            }
            if (type.getKind() != TypeKind.DECLARED
                    && type.getKind() != TypeKind.ERROR) {
                return type;
            }
            DeclaredType declared = (DeclaredType) type;
            Element element = declared.asElement();
            if (!(element instanceof TypeElement)) {
                return type;
            }
            List<TypeMirror> arguments = new ArrayList<TypeMirror>();
            for (TypeMirror argument : declared.getTypeArguments()) {
                arguments.add(substituteType(argument, substitutions, visiting));
            }
            TypeMirror[] argumentArray = arguments.toArray(new TypeMirror[arguments.size()]);
            TypeElement declaredElement = (TypeElement) element;
            TypeMirror enclosing = declared.getEnclosingType();
            if (enclosing != null && enclosing.getKind() == TypeKind.DECLARED) {
                TypeMirror resolvedEnclosing = substituteType(
                        enclosing, substitutions, visiting);
                if (resolvedEnclosing != null
                        && resolvedEnclosing.getKind() == TypeKind.DECLARED) {
                    try {
                        return types.getDeclaredType(
                                (DeclaredType) resolvedEnclosing,
                                declaredElement,
                                argumentArray);
                    }
                    catch (IllegalArgumentException ignored) {
                        // Static nested types are reconstructed without an enclosing type below.
                    }
                }
            }
            try {
                return types.getDeclaredType(declaredElement, argumentArray);
            }
            catch (IllegalArgumentException incompleteSymbol) {
                return type;
            }
        }
        finally {
            visiting.remove(visitKey);
        }
    }
}
