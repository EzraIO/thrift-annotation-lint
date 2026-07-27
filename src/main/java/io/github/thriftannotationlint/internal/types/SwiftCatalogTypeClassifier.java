package io.github.thriftannotationlint.internal.types;

import io.github.thriftannotationlint.internal.model.SwiftAnnotations;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Applies Facebook Swift's declaration-type classification order. */
final class SwiftCatalogTypeClassifier {
    enum Kind {
        BOXED,
        STRING,
        BINARY,
        ENUM,
        MAP,
        SET,
        LIST,
        STRUCT,
        UNION,
        UNKNOWN
    }

    static final class CatalogType {
        final Kind kind;
        final DeclaredType view;
        final String typeName;

        CatalogType(
                Kind kind,
                DeclaredType view,
                String typeName) {
            this.kind = kind;
            this.view = view;
            this.typeName = typeName;
        }
    }

    private final TypeHierarchyResolver hierarchyResolver;
    private final JavaTypeIdentityFormatter identityFormatter;
    private final TypeElement byteBufferType;
    private final TypeElement listType;
    private final TypeElement mapType;
    private final TypeElement setType;
    private final TypeElement iterableType;

    SwiftCatalogTypeClassifier(
            Elements elements,
            TypeHierarchyResolver hierarchyResolver,
            JavaTypeIdentityFormatter identityFormatter) {
        this.hierarchyResolver = hierarchyResolver;
        this.identityFormatter = identityFormatter;
        this.byteBufferType = elements.getTypeElement("java.nio.ByteBuffer");
        this.listType = elements.getTypeElement("java.util.List");
        this.mapType = elements.getTypeElement("java.util.Map");
        this.setType = elements.getTypeElement("java.util.Set");
        this.iterableType = elements.getTypeElement("java.lang.Iterable");
    }

    boolean isSupported(TypeMirror type) {
        return isSupported(type, new HashSet<String>());
    }

    /**
     * Returns the Java type identity used by Swift's normalized {@code ThriftType}.
     * Collection implementations intentionally collapse to their wire container interface, while
     * coerced scalar types (for example {@code int} and {@code Integer}) remain distinct.
     */
    String normalizedType(TypeMirror type) {
        return normalizedType(type, new HashSet<String>(), false);
    }

    boolean isContainerType(TypeMirror type) {
        if (type == null || type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        return isContainerKind(classify((DeclaredType) type).kind);
    }

    /**
     * Returns a resolved type that proves Swift will classify the source declaration as a
     * container. javac can retain a stale root DeclaredType when a generated direct supertype is
     * completed in a later round, so the element hierarchy is used as a bounded fallback.
     */
    TypeMirror containerClassificationType(TypeMirror candidate) {
        if (candidate == null || candidate.getKind() != TypeKind.DECLARED) {
            return null;
        }
        CatalogType catalogType = classify((DeclaredType) candidate);
        return isContainerKind(catalogType.kind) ? catalogType.view : null;
    }

    List<TypeMirror> containerTypeArguments(TypeMirror type) {
        List<TypeMirror> result = new ArrayList<TypeMirror>();
        TypeMirror containerType = containerClassificationType(type);
        if (containerType != null) {
            result.addAll(((DeclaredType) containerType).getTypeArguments());
        }
        return result;
    }

    CatalogType classify(DeclaredType declaredType) {
        Element declaredElement = declaredType.asElement();
        if (!(declaredElement instanceof TypeElement)) {
            return new CatalogType(Kind.UNKNOWN, null, null);
        }
        TypeElement typeElement = (TypeElement) declaredElement;
        String typeName = typeElement.getQualifiedName().toString();
        if ("java.lang.Object".equals(typeName)) {
            return new CatalogType(Kind.UNKNOWN, null, typeName);
        }
        if (isSupportedBoxedType(typeName)) {
            return new CatalogType(Kind.BOXED, declaredType, typeName);
        }
        if ("java.lang.String".equals(typeName)) {
            return new CatalogType(Kind.STRING, declaredType, typeName);
        }

        // Keep this precedence aligned with ThriftCatalog.buildThriftTypeInternal().
        DeclaredType view = hierarchyResolver.asSupertype(declaredType, byteBufferType);
        if (view != null) {
            return new CatalogType(Kind.BINARY, view, typeName);
        }
        if (typeElement.getKind() == ElementKind.ENUM) {
            return new CatalogType(Kind.ENUM, declaredType, typeName);
        }
        view = hierarchyResolver.asSupertype(declaredType, mapType);
        if (view != null) {
            return new CatalogType(Kind.MAP, view, typeName);
        }
        view = hierarchyResolver.asSupertype(declaredType, setType);
        if (view != null) {
            return new CatalogType(Kind.SET, view, typeName);
        }
        view = hierarchyResolver.asSupertype(declaredType, iterableType);
        if (view != null) {
            return new CatalogType(Kind.LIST, view, typeName);
        }
        if (SwiftAnnotations.has(typeElement, SwiftAnnotations.THRIFT_STRUCT)) {
            return new CatalogType(Kind.STRUCT, declaredType, typeName);
        }
        if (SwiftAnnotations.has(typeElement, SwiftAnnotations.THRIFT_UNION)) {
            return new CatalogType(Kind.UNION, declaredType, typeName);
        }
        return new CatalogType(Kind.UNKNOWN, null, typeName);
    }

    TypeElement canonicalType(Kind kind) {
        if (kind == Kind.BINARY) {
            return byteBufferType;
        }
        if (kind == Kind.MAP) {
            return mapType;
        }
        if (kind == Kind.SET) {
            return setType;
        }
        if (kind == Kind.LIST) {
            return listType;
        }
        return null;
    }

    TypeElement listType() {
        return listType;
    }

    private boolean isSupported(TypeMirror type, Set<String> visiting) {
        if (type == null) {
            return false;
        }
        if (type.getKind() == TypeKind.ERROR) {
            // javac owns unresolved-symbol diagnostics; reporting AW4001 as well is noisy.
            return true;
        }
        if (isSupportedPrimitive(type.getKind())) {
            return true;
        }
        if (type.getKind() == TypeKind.ARRAY) {
            return isSupportedArray((ArrayType) type);
        }
        if (type.getKind() == TypeKind.TYPEVAR) {
            if (identityFormatter.isModelTypeVariable(type)) {
                // A model type variable is resolved from the concrete struct Type at runtime.
                // Concrete inherited/member views are resolved by Types.asMemberOf whenever javac
                // exposes an instantiated model to the processor.
                return true;
            }
            // A method or constructor type variable is not substituted from the model Type.
            // Guava TypeToken uses its first upper bound as the raw type, so an unbounded variable
            // resolves to Object (unsupported) while a supported explicit bound can be encoded.
            TypeMirror upperBound = ((TypeVariable) type).getUpperBound();
            return upperBound != null && isSupported(upperBound, visiting);
        }
        if (type.getKind() == TypeKind.WILDCARD) {
            WildcardType wildcard = (WildcardType) type;
            TypeMirror upperBound = wildcard.getExtendsBound();
            return upperBound != null && isSupported(upperBound, visiting);
        }
        if (type.getKind() == TypeKind.INTERSECTION) {
            List<? extends TypeMirror> bounds = ((IntersectionType) type).getBounds();
            return !bounds.isEmpty() && isSupported(bounds.get(0), visiting);
        }
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }

        String visitKey = "SUPPORTED:" + type;
        if (!visiting.add(visitKey)) {
            return false;
        }
        try {
            CatalogType catalogType = classify((DeclaredType) type);
            if (catalogType.kind == Kind.BOXED
                    || catalogType.kind == Kind.STRING
                    || catalogType.kind == Kind.BINARY
                    || catalogType.kind == Kind.ENUM
                    || catalogType.kind == Kind.STRUCT
                    || catalogType.kind == Kind.UNION) {
                return true;
            }
            if (!isContainerKind(catalogType.kind)) {
                // Custom ThriftCatalog coercions cannot be known safely at compile time.
                return false;
            }
            List<? extends TypeMirror> arguments = catalogType.view.getTypeArguments();
            int expectedArguments = catalogType.kind == Kind.MAP ? 2 : 1;
            if (arguments.size() != expectedArguments) {
                return false;
            }
            for (TypeMirror argument : arguments) {
                if (!isSupported(argument, visiting)) {
                    return false;
                }
            }
            return true;
        }
        finally {
            visiting.remove(visitKey);
        }
    }

    private String normalizedType(
            TypeMirror type,
            Set<String> visiting,
            boolean preserveStructArguments) {
        if (type == null) {
            return null;
        }
        if (type.getKind() == TypeKind.ERROR) {
            return "ERROR";
        }
        if (isSupportedPrimitive(type.getKind())) {
            return type.getKind().name();
        }
        if (type.getKind() == TypeKind.ARRAY) {
            ArrayType array = (ArrayType) type;
            return isSupportedArray(array)
                    ? array.getComponentType().getKind().name() + "[]"
                    : null;
        }
        if (type.getKind() == TypeKind.TYPEVAR) {
            if (identityFormatter.isModelTypeVariable(type)) {
                // Distinct model type variables cannot be proven incompatible at declaration
                // time. Their actual arguments are checked when javac exposes a concrete view.
                return "DEFERRED_TYPE_VARIABLE";
            }
            TypeMirror upperBound = ((TypeVariable) type).getUpperBound();
            TypeMirror rawUpperBound = firstUpperBound(upperBound);
            if (preserveStructArguments && isAnnotatedStructType(rawUpperBound)) {
                // Recursive struct references nested in containers retain the exact reflective
                // Type, even though TypeToken uses the bound as its raw class.
                return identityFormatter.typeVariableIdentity(type);
            }
            // Top-level values normalize to the Thrift type of TypeToken's first upper bound.
            return rawUpperBound == null
                    ? null
                    : normalizedType(rawUpperBound, visiting, preserveStructArguments);
        }
        if (type.getKind() == TypeKind.WILDCARD) {
            WildcardType wildcard = (WildcardType) type;
            TypeMirror upperBound = wildcard.getExtendsBound();
            if (preserveStructArguments && isAnnotatedStructType(upperBound)) {
                return identityFormatter.javaTypeIdentity(wildcard, visiting, true);
            }
            return upperBound == null
                    ? null
                    : normalizedType(upperBound, visiting, preserveStructArguments);
        }
        if (type.getKind() == TypeKind.INTERSECTION) {
            List<? extends TypeMirror> bounds = ((IntersectionType) type).getBounds();
            return bounds.isEmpty()
                    ? null
                    : normalizedType(bounds.get(0), visiting, preserveStructArguments);
        }
        if (type.getKind() != TypeKind.DECLARED) {
            return null;
        }

        String visitKey = "NORMALIZED:" + preserveStructArguments + ":" + type;
        if (!visiting.add(visitKey)) {
            return null;
        }
        try {
            DeclaredType declaredType = (DeclaredType) type;
            CatalogType catalogType = classify(declaredType);
            if (catalogType.kind == Kind.BOXED || catalogType.kind == Kind.STRING) {
                return catalogType.typeName;
            }
            if (catalogType.kind == Kind.BINARY) {
                return "BINARY:java.nio.ByteBuffer";
            }
            if (catalogType.kind == Kind.ENUM) {
                return "ENUM:" + catalogType.typeName;
            }
            if (catalogType.kind == Kind.MAP) {
                List<? extends TypeMirror> arguments = catalogType.view.getTypeArguments();
                if (arguments.size() != 2) {
                    return null;
                }
                String key = normalizedType(arguments.get(0), visiting, true);
                String value = normalizedType(arguments.get(1), visiting, true);
                return key == null || value == null ? null : "MAP<" + key + "," + value + ">";
            }
            if (catalogType.kind == Kind.SET || catalogType.kind == Kind.LIST) {
                List<? extends TypeMirror> arguments = catalogType.view.getTypeArguments();
                if (arguments.size() != 1) {
                    return null;
                }
                String value = normalizedType(arguments.get(0), visiting, true);
                if (value == null) {
                    return null;
                }
                return (catalogType.kind == Kind.SET ? "SET<" : "LIST<") + value + ">";
            }
            if (catalogType.kind == Kind.STRUCT || catalogType.kind == Kind.UNION) {
                List<? extends TypeMirror> arguments = declaredType.getTypeArguments();
                if (arguments.isEmpty() || !preserveStructArguments) {
                    return "STRUCT:" + catalogType.typeName;
                }
                List<String> normalizedArguments = new ArrayList<String>();
                for (TypeMirror argument : arguments) {
                    // Swift's recursive reference for a struct nested in a container retains the
                    // exact Java parameterized Type. Do not collapse ArrayList<T> to List<T> here.
                    normalizedArguments.add(identityFormatter.javaTypeIdentity(
                            argument, visiting, true));
                }
                return "STRUCT:" + catalogType.typeName
                        + "<" + join(normalizedArguments) + ">";
            }
            return null;
        }
        finally {
            visiting.remove(visitKey);
        }
    }

    private boolean isContainerKind(Kind kind) {
        return kind == Kind.MAP || kind == Kind.SET || kind == Kind.LIST;
    }

    private boolean isSupportedArray(ArrayType array) {
        TypeKind component = array.getComponentType().getKind();
        return component == TypeKind.BOOLEAN
                || component == TypeKind.BYTE
                || component == TypeKind.SHORT
                || component == TypeKind.INT
                || component == TypeKind.LONG
                || component == TypeKind.DOUBLE;
    }

    private boolean isSupportedPrimitive(TypeKind kind) {
        return kind == TypeKind.BOOLEAN
                || kind == TypeKind.BYTE
                || kind == TypeKind.SHORT
                || kind == TypeKind.INT
                || kind == TypeKind.LONG
                || kind == TypeKind.FLOAT
                || kind == TypeKind.DOUBLE;
    }

    private boolean isSupportedBoxedType(String typeName) {
        return "java.lang.Boolean".equals(typeName)
                || "java.lang.Byte".equals(typeName)
                || "java.lang.Short".equals(typeName)
                || "java.lang.Integer".equals(typeName)
                || "java.lang.Long".equals(typeName)
                || "java.lang.Float".equals(typeName)
                || "java.lang.Double".equals(typeName);
    }

    private boolean isAnnotatedStructType(TypeMirror type) {
        if (type == null || type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        Element element = ((DeclaredType) type).asElement();
        return element instanceof TypeElement
                && (SwiftAnnotations.has(element, SwiftAnnotations.THRIFT_STRUCT)
                || SwiftAnnotations.has(element, SwiftAnnotations.THRIFT_UNION));
    }

    private TypeMirror firstUpperBound(TypeMirror bound) {
        if (bound != null && bound.getKind() == TypeKind.INTERSECTION) {
            List<? extends TypeMirror> bounds = ((IntersectionType) bound).getBounds();
            return bounds.isEmpty() ? null : bounds.get(0);
        }
        return bound;
    }

    private String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(value);
        }
        return result.toString();
    }
}
