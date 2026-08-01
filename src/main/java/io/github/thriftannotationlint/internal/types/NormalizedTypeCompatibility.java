package io.github.thriftannotationlint.internal.types;

import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Compares normalized field types and their canonical Swift codec shapes. */
final class NormalizedTypeCompatibility {
    private final Types types;
    private final TypeHierarchyResolver hierarchyResolver;
    private final WireTypeClassifier classifier;
    private final JavaTypeIdentityFormatter identityFormatter;

    NormalizedTypeCompatibility(
            Types types,
            TypeHierarchyResolver hierarchyResolver,
            WireTypeClassifier classifier,
            JavaTypeIdentityFormatter identityFormatter) {
        this.types = types;
        this.hierarchyResolver = hierarchyResolver;
        this.classifier = classifier;
        this.identityFormatter = identityFormatter;
    }

    /**
     * Compares normalized types while treating a type variable as an unknown value that will be
     * resolved when a generic model is instantiated. Container structure must still match, and
     * callers must compare every pair in a logical field so a type variable cannot hide two
     * incompatible concrete declarations.
     */
    boolean areCompatibleNormalizedTypes(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.equals(right)
                || WireTypeTokens.DEFERRED_TYPE_VARIABLE.equals(left)
                || WireTypeTokens.DEFERRED_TYPE_VARIABLE.equals(right)) {
            return true;
        }

        String leftKind = genericKind(left);
        if (leftKind == null || !leftKind.equals(genericKind(right))) {
            return false;
        }
        List<String> leftArguments = containerArguments(left);
        List<String> rightArguments = containerArguments(right);
        if (leftArguments == null
                || rightArguments == null
                || leftArguments.size() != rightArguments.size()) {
            return false;
        }
        for (int index = 0; index < leftArguments.size(); index++) {
            if (!areCompatibleNormalizedTypes(
                    leftArguments.get(index), rightArguments.get(index))) {
                return false;
            }
        }
        return true;
    }

    boolean areCompatibleCarrierShapes(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.equals(right)
                || WireTypeTokens.DEFERRED.equals(left)
                || WireTypeTokens.DEFERRED.equals(right)) {
            return true;
        }
        String leftKind = genericKind(left);
        if (leftKind == null || !leftKind.equals(genericKind(right))) {
            return false;
        }
        List<String> leftArguments = containerArguments(left);
        List<String> rightArguments = containerArguments(right);
        if (leftArguments == null || rightArguments == null
                || leftArguments.size() != rightArguments.size()) {
            return false;
        }
        for (int index = 0; index < leftArguments.size(); index++) {
            if (!areCompatibleCarrierShapes(
                    leftArguments.get(index), rightArguments.get(index))) {
                return false;
            }
        }
        return true;
    }

    /** Returns whether this extraction type can be passed to Swift's canonical codec shape. */
    boolean providesCanonicalValue(TypeMirror source) {
        return providesCanonicalValue(source, ThriftAnnotationDialect.FACEBOOK_SWIFT);
    }

    boolean providesCanonicalValue(TypeMirror source, ThriftAnnotationDialect dialect) {
        return isCanonicalShapeCompatible(source, dialect, true, new HashSet<String>());
    }

    /** Returns whether Swift's canonical decoded value can be injected into this Java type. */
    boolean acceptsDecodedValue(TypeMirror target) {
        return acceptsDecodedValue(target, ThriftAnnotationDialect.FACEBOOK_SWIFT);
    }

    boolean acceptsDecodedValue(TypeMirror target, ThriftAnnotationDialect dialect) {
        return isCanonicalShapeCompatible(target, dialect, false, new HashSet<String>());
    }

    String canonicalDecodedTypeName(TypeMirror target) {
        return canonicalDecodedTypeName(target, ThriftAnnotationDialect.FACEBOOK_SWIFT);
    }

    String canonicalDecodedTypeName(TypeMirror target, ThriftAnnotationDialect dialect) {
        TypeMirror canonical = canonicalDecodedType(target, dialect);
        return canonical == null ? null : types.erasure(canonical).toString();
    }

    private TypeMirror canonicalDecodedType(
            TypeMirror type,
            ThriftAnnotationDialect dialect) {
        if (type == null) {
            return null;
        }
        if (type.getKind() == TypeKind.TYPEVAR) {
            if (identityFormatter.isModelTypeVariable(type)) {
                return null;
            }
            return canonicalDecodedType(((TypeVariable) type).getUpperBound(), dialect);
        }
        if (type.getKind() == TypeKind.WILDCARD) {
            return canonicalDecodedType(((WildcardType) type).getExtendsBound(), dialect);
        }
        if (type.getKind() == TypeKind.INTERSECTION) {
            List<? extends TypeMirror> bounds = ((IntersectionType) type).getBounds();
            return bounds.isEmpty() ? null : canonicalDecodedType(bounds.get(0), dialect);
        }
        if (type.getKind() != TypeKind.DECLARED) {
            return null;
        }
        DeclaredType declared = (DeclaredType) type;
        WireTypeClassifier.CatalogType catalogType = classifier.classify(declared, dialect);
        if (catalogType.kind == WireTypeClassifier.Kind.ENUM) {
            // ThriftCatalog classifies Enum before Map, Set, and Iterable. An enum is allowed to
            // implement a container interface without changing its canonical codec shape.
            return declared;
        }
        if (catalogType.kind == WireTypeClassifier.Kind.OPTIONAL) {
            return declared;
        }
        TypeElement canonicalType = classifier.canonicalType(catalogType.kind);
        return canonicalType == null ? null : canonicalType.asType();
    }

    private boolean isCanonicalShapeCompatible(
            TypeMirror type,
            ThriftAnnotationDialect dialect,
            boolean readable,
            Set<String> visiting) {
        if (type == null
                || type.getKind() == TypeKind.ERROR
                || identityFormatter.isModelTypeVariable(type)) {
            return true;
        }
        if (type.getKind() == TypeKind.TYPEVAR) {
            return isCanonicalShapeCompatible(
                    ((TypeVariable) type).getUpperBound(), dialect, readable, visiting);
        }
        if (type.getKind() == TypeKind.WILDCARD) {
            return isCanonicalShapeCompatible(
                    ((WildcardType) type).getExtendsBound(), dialect, readable, visiting);
        }
        if (type.getKind() == TypeKind.INTERSECTION) {
            List<? extends TypeMirror> bounds = ((IntersectionType) type).getBounds();
            return bounds.isEmpty()
                    || isCanonicalShapeCompatible(bounds.get(0), dialect, readable, visiting);
        }
        if (type.getKind() != TypeKind.DECLARED) {
            return true;
        }

        String visitKey = (readable
                ? WireTypeTokens.READ_VISIT_PREFIX
                : WireTypeTokens.WRITE_VISIT_PREFIX) + type;
        if (!visiting.add(visitKey)) {
            return true;
        }
        try {
            DeclaredType declared = (DeclaredType) type;
            WireTypeClassifier.CatalogType catalogType =
                    classifier.classify(declared, dialect);
            if (catalogType.kind == WireTypeClassifier.Kind.ENUM) {
                return true;
            }
            if (catalogType.kind == WireTypeClassifier.Kind.OPTIONAL) {
                for (TypeMirror argument : catalogType.view.getTypeArguments()) {
                    if (!isCanonicalShapeCompatible(
                            argument, dialect, readable, visiting)) {
                        return false;
                    }
                }
                return true;
            }
            TypeElement canonicalType = classifier.canonicalType(catalogType.kind);
            if (canonicalType == null || catalogType.view == null) {
                return true;
            }

            boolean rawCompatible;
            try {
                if (readable) {
                    // The resolved ByteBuffer/Map/Set view already proves read assignability.
                    // Iterable codecs canonicalize to List, so that one case needs the stronger
                    // List-subtype proof (using the same generated-hierarchy fallback).
                    rawCompatible = catalogType.kind != WireTypeClassifier.Kind.LIST
                            || hierarchyResolver.asSupertype(
                            declared, classifier.listType()) != null;
                }
                else {
                    Element targetElement = declared.asElement();
                    rawCompatible = targetElement instanceof TypeElement
                            && hierarchyResolver.asSupertype(
                            (DeclaredType) canonicalType.asType(),
                            (TypeElement) targetElement) != null;
                }
            }
            catch (IllegalArgumentException ignored) {
                return true;
            }
            if (!rawCompatible) {
                return false;
            }
            for (TypeMirror argument : catalogType.view.getTypeArguments()) {
                if (!isCanonicalShapeCompatible(argument, dialect, readable, visiting)) {
                    return false;
                }
            }
            return true;
        }
        finally {
            visiting.remove(visitKey);
        }
    }

    private String genericKind(String normalizedType) {
        if (normalizedType.startsWith(WireTypeTokens.LIST_PREFIX)) {
            return WireTypeTokens.LIST_KIND;
        }
        if (normalizedType.startsWith(WireTypeTokens.SET_PREFIX)) {
            return WireTypeTokens.SET_KIND;
        }
        if (normalizedType.startsWith(WireTypeTokens.MAP_PREFIX)) {
            return WireTypeTokens.MAP_KIND;
        }
        if (normalizedType.startsWith(WireTypeTokens.OPTIONAL_PREFIX)) {
            return WireTypeTokens.OPTIONAL_KIND;
        }
        if (normalizedType.startsWith(WireTypeTokens.STRUCT_PREFIX)) {
            int opening = normalizedType.indexOf('<');
            return opening < 0 ? null : normalizedType.substring(0, opening);
        }
        if (normalizedType.startsWith(WireTypeTokens.JAVA_PREFIX)) {
            int opening = normalizedType.indexOf('<');
            return opening < 0 ? null : normalizedType.substring(0, opening);
        }
        if (normalizedType.startsWith(WireTypeTokens.JAVA_ARRAY_PREFIX)) {
            return WireTypeTokens.JAVA_ARRAY_KIND;
        }
        if (normalizedType.startsWith(WireTypeTokens.JAVA_EXTENDS_PREFIX)) {
            return WireTypeTokens.JAVA_EXTENDS_KIND;
        }
        if (normalizedType.startsWith(WireTypeTokens.JAVA_SUPER_PREFIX)) {
            return WireTypeTokens.JAVA_SUPER_KIND;
        }
        if (normalizedType.startsWith(WireTypeTokens.JAVA_OWNER_PREFIX)) {
            return WireTypeTokens.JAVA_OWNER_KIND;
        }
        return null;
    }

    private List<String> containerArguments(String normalizedType) {
        int opening = normalizedType.indexOf('<');
        if (opening <= 0 || !normalizedType.endsWith(">")) {
            return null;
        }
        List<String> arguments = new ArrayList<String>();
        int depth = 0;
        int start = opening + 1;
        for (int index = start; index < normalizedType.length() - 1; index++) {
            char character = normalizedType.charAt(index);
            if (character == '<') {
                depth++;
            }
            else if (character == '>') {
                if (depth == 0) {
                    return null;
                }
                depth--;
            }
            else if (character == ',' && depth == 0) {
                arguments.add(normalizedType.substring(start, index));
                start = index + 1;
            }
        }
        if (depth != 0 || start >= normalizedType.length() - 1) {
            return null;
        }
        arguments.add(normalizedType.substring(start, normalizedType.length() - 1));
        return arguments;
    }
}
