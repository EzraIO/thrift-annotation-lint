package io.github.thriftannotationlint.internal.types;

import io.github.thriftannotationlint.internal.model.ThriftAnnotations;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Applies the declaration-type classification order shared by supported Thrift codecs. */
final class WireTypeClassifier {
    enum Kind {
        BOXED,
        STRING,
        BINARY,
        ENUM,
        MAP,
        SET,
        LIST,
        OPTIONAL,
        STRUCT,
        UNION,
        UNKNOWN
    }

    static final class CatalogType {
        final Kind kind;
        final DeclaredType view;
        final String typeName;

        CatalogType(Kind kind, DeclaredType view, String typeName) {
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
    private final TypeElement optionalType;
    private final TypeInspectionMetrics metrics;
    private final DialectTypePolicy dialectPolicy = new DialectTypePolicy();
    private final Map<String, CatalogType> roundClassifications =
            new LinkedHashMap<String, CatalogType>();

    WireTypeClassifier(
            Elements elements,
            TypeHierarchyResolver hierarchyResolver,
            JavaTypeIdentityFormatter identityFormatter) {
        this(elements, hierarchyResolver, identityFormatter, null);
    }

    WireTypeClassifier(
            Elements elements,
            TypeHierarchyResolver hierarchyResolver,
            JavaTypeIdentityFormatter identityFormatter,
            TypeInspectionMetrics metrics) {
        this.hierarchyResolver = hierarchyResolver;
        this.identityFormatter = identityFormatter;
        this.metrics = metrics;
        this.byteBufferType = elements.getTypeElement("java.nio.ByteBuffer");
        this.listType = elements.getTypeElement("java.util.List");
        this.mapType = elements.getTypeElement("java.util.Map");
        this.setType = elements.getTypeElement("java.util.Set");
        this.iterableType = elements.getTypeElement("java.lang.Iterable");
        this.optionalType = elements.getTypeElement("java.util.Optional");
    }

    void beginRound() {
        roundClassifications.clear();
        hierarchyResolver.beginRound();
    }

    boolean isContainerType(TypeMirror type) {
        if (type == null || type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        return isContainerKind(classify((DeclaredType) type).kind);
    }

    /**
     * Returns a resolved type that proves the source declaration is classified as a container.
     * The hierarchy fallback handles stale root mirrors completed in a later processing round.
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

    List<TypeMirror> nestedWireTypeArguments(
            TypeMirror type,
            ThriftAnnotationDialect dialect) {
        List<TypeMirror> result = new ArrayList<TypeMirror>();
        if (type == null || type.getKind() != TypeKind.DECLARED) {
            return result;
        }
        CatalogType catalogType = classify((DeclaredType) type, dialect);
        if (isContainerKind(catalogType.kind) || catalogType.kind == Kind.OPTIONAL) {
            result.addAll(catalogType.view.getTypeArguments());
        }
        return result;
    }

    CatalogType classify(DeclaredType declaredType) {
        return classify(declaredType, ThriftAnnotationDialect.FACEBOOK_SWIFT);
    }

    CatalogType classify(DeclaredType declaredType, ThriftAnnotationDialect dialect) {
        String cacheKey = dialect.name() + "\u0000"
                + identityFormatter.exactJavaTypeIdentity(declaredType);
        CatalogType cached = roundClassifications.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        CatalogType result = classifyUncached(declaredType, dialect);
        roundClassifications.put(cacheKey, result);
        if (metrics != null) {
            metrics.classification();
        }
        return result;
    }

    private CatalogType classifyUncached(
            DeclaredType declaredType,
            ThriftAnnotationDialect dialect) {
        Element declaredElement = declaredType.asElement();
        if (!(declaredElement instanceof TypeElement)) {
            return new CatalogType(Kind.UNKNOWN, null, null);
        }
        TypeElement typeElement = (TypeElement) declaredElement;
        String typeName = typeElement.getQualifiedName().toString();
        CatalogType scalar = scalarType(declaredType, dialect, typeElement, typeName);
        if (scalar != null) {
            return scalar;
        }
        return hierarchyType(declaredType, dialect, typeElement, typeName);
    }

    private CatalogType scalarType(
            DeclaredType declaredType,
            ThriftAnnotationDialect dialect,
            TypeElement typeElement,
            String typeName) {
        if ("java.lang.Object".equals(typeName)) {
            return new CatalogType(Kind.UNKNOWN, null, typeName);
        }
        if (isSupportedBoxedType(typeName)) {
            return new CatalogType(Kind.BOXED, declaredType, typeName);
        }
        if ("java.lang.String".equals(typeName)) {
            return new CatalogType(Kind.STRING, declaredType, typeName);
        }
        CatalogType primitiveOptional = primitiveOptional(declaredType, dialect, typeName);
        if (primitiveOptional != null) {
            return primitiveOptional;
        }
        DeclaredType binaryView = hierarchyResolver.asSupertype(declaredType, byteBufferType);
        if (binaryView != null) {
            return new CatalogType(Kind.BINARY, binaryView, typeName);
        }
        if (typeElement.getKind() == ElementKind.ENUM) {
            return new CatalogType(Kind.ENUM, declaredType, typeName);
        }
        return null;
    }

    private CatalogType primitiveOptional(
            DeclaredType declaredType,
            ThriftAnnotationDialect dialect,
            String typeName) {
        if (!dialectPolicy.supportsOptional(dialect)) {
            return null;
        }
        if ("java.util.OptionalInt".equals(typeName)) {
            return new CatalogType(Kind.OPTIONAL, declaredType, "java.lang.Integer");
        }
        if ("java.util.OptionalLong".equals(typeName)) {
            return new CatalogType(Kind.OPTIONAL, declaredType, "java.lang.Long");
        }
        if ("java.util.OptionalDouble".equals(typeName)) {
            return new CatalogType(Kind.OPTIONAL, declaredType, "java.lang.Double");
        }
        return null;
    }

    private CatalogType hierarchyType(
            DeclaredType declaredType,
            ThriftAnnotationDialect dialect,
            TypeElement typeElement,
            String typeName) {
        CatalogType container = containerType(declaredType, dialect, typeName);
        if (container != null) {
            return container;
        }
        if (ThriftAnnotations.has(typeElement, dialect.thriftStruct())) {
            return new CatalogType(Kind.STRUCT, declaredType, typeName);
        }
        if (ThriftAnnotations.has(typeElement, dialect.thriftUnion())) {
            return new CatalogType(Kind.UNION, declaredType, typeName);
        }
        if (isForeignModel(typeElement, dialect)) {
            return new CatalogType(Kind.STRUCT, declaredType, typeName);
        }
        return new CatalogType(Kind.UNKNOWN, null, typeName);
    }

    private CatalogType containerType(
            DeclaredType declaredType,
            ThriftAnnotationDialect dialect,
            String typeName) {
        DeclaredType view = hierarchyResolver.asSupertype(declaredType, mapType);
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
        if (dialectPolicy.supportsOptional(dialect)) {
            view = hierarchyResolver.asSupertype(declaredType, optionalType);
            if (view != null) {
                return new CatalogType(Kind.OPTIONAL, view, typeName);
            }
        }
        return null;
    }

    private boolean isForeignModel(TypeElement typeElement, ThriftAnnotationDialect dialect) {
        for (ThriftAnnotationDialect other : ThriftAnnotationDialect.values()) {
            if (other != dialect
                    && (ThriftAnnotations.has(typeElement, other.thriftStruct())
                    || ThriftAnnotations.has(typeElement, other.thriftUnion()))) {
                return true;
            }
        }
        return false;
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

    boolean isContainerKind(Kind kind) {
        return kind == Kind.MAP || kind == Kind.SET || kind == Kind.LIST;
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
}
