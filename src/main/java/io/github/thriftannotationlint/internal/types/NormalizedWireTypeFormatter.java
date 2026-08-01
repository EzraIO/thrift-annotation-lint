package io.github.thriftannotationlint.internal.types;

import io.github.thriftannotationlint.internal.model.ThriftAnnotations;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Produces dialect-aware normalized wire identities. */
final class NormalizedWireTypeFormatter {
    private final WireTypeClassifier classifier;
    private final JavaTypeIdentityFormatter identityFormatter;

    NormalizedWireTypeFormatter(
            WireTypeClassifier classifier,
            JavaTypeIdentityFormatter identityFormatter) {
        this.classifier = classifier;
        this.identityFormatter = identityFormatter;
    }

    String format(TypeMirror type) {
        return format(type, ThriftAnnotationDialect.FACEBOOK_SWIFT);
    }

    String format(TypeMirror type, ThriftAnnotationDialect dialect) {
        return format(type, dialect, new HashSet<String>(), false);
    }

    private String format(
            TypeMirror type,
            ThriftAnnotationDialect dialect,
            Set<String> visiting,
            boolean preserveStructArguments) {
        if (type == null) {
            return null;
        }
        String simple = simpleType(type);
        if (simple != null || type.getKind() == TypeKind.ERROR) {
            return simple;
        }
        if (type.getKind() == TypeKind.TYPEVAR) {
            return formatTypeVariable(
                    (TypeVariable) type, dialect, visiting, preserveStructArguments);
        }
        if (type.getKind() == TypeKind.WILDCARD) {
            return formatWildcard(
                    (WildcardType) type, dialect, visiting, preserveStructArguments);
        }
        if (type.getKind() == TypeKind.INTERSECTION) {
            List<? extends TypeMirror> bounds = ((IntersectionType) type).getBounds();
            return bounds.isEmpty()
                    ? null
                    : format(bounds.get(0), dialect, visiting, preserveStructArguments);
        }
        if (type.getKind() != TypeKind.DECLARED) {
            return null;
        }
        return formatDeclared(
                (DeclaredType) type, dialect, visiting, preserveStructArguments);
    }

    private String simpleType(TypeMirror type) {
        if (type.getKind() == TypeKind.ERROR) {
            return "ERROR";
        }
        if (WireTypeSupport.isSupportedPrimitive(type.getKind())) {
            return type.getKind().name();
        }
        if (type.getKind() == TypeKind.ARRAY) {
            ArrayType array = (ArrayType) type;
            return WireTypeSupport.isSupportedArray(array)
                    ? array.getComponentType().getKind().name() + "[]"
                    : null;
        }
        return null;
    }

    private String formatTypeVariable(
            TypeVariable type,
            ThriftAnnotationDialect dialect,
            Set<String> visiting,
            boolean preserveStructArguments) {
        if (identityFormatter.isModelTypeVariable(type)) {
            return "DEFERRED_TYPE_VARIABLE";
        }
        TypeMirror rawUpperBound = firstUpperBound(type.getUpperBound());
        if (preserveStructArguments && isAnnotatedStructType(rawUpperBound)) {
            return identityFormatter.typeVariableIdentity(type);
        }
        return rawUpperBound == null
                ? null
                : format(rawUpperBound, dialect, visiting, preserveStructArguments);
    }

    private String formatWildcard(
            WildcardType wildcard,
            ThriftAnnotationDialect dialect,
            Set<String> visiting,
            boolean preserveStructArguments) {
        TypeMirror upperBound = wildcard.getExtendsBound();
        if (preserveStructArguments && isAnnotatedStructType(upperBound)) {
            return identityFormatter.javaTypeIdentity(wildcard, visiting, true);
        }
        return upperBound == null
                ? null
                : format(upperBound, dialect, visiting, preserveStructArguments);
    }

    private String formatDeclared(
            DeclaredType type,
            ThriftAnnotationDialect dialect,
            Set<String> visiting,
            boolean preserveStructArguments) {
        String visitKey = "NORMALIZED:" + preserveStructArguments + ":" + type;
        if (!visiting.add(visitKey)) {
            return null;
        }
        try {
            WireTypeClassifier.CatalogType catalogType = classifier.classify(type, dialect);
            return formatCatalogType(
                    type, catalogType, dialect, visiting, preserveStructArguments);
        }
        finally {
            visiting.remove(visitKey);
        }
    }

    private String formatCatalogType(
            DeclaredType declaredType,
            WireTypeClassifier.CatalogType catalogType,
            ThriftAnnotationDialect dialect,
            Set<String> visiting,
            boolean preserveStructArguments) {
        if (catalogType.kind == WireTypeClassifier.Kind.BOXED
                || catalogType.kind == WireTypeClassifier.Kind.STRING) {
            return catalogType.typeName;
        }
        if (catalogType.kind == WireTypeClassifier.Kind.BINARY) {
            return "BINARY:java.nio.ByteBuffer";
        }
        if (catalogType.kind == WireTypeClassifier.Kind.ENUM) {
            return "ENUM:" + catalogType.typeName;
        }
        if (catalogType.kind == WireTypeClassifier.Kind.OPTIONAL) {
            return formatOptional(catalogType, dialect, visiting);
        }
        if (catalogType.kind == WireTypeClassifier.Kind.MAP) {
            return formatMap(catalogType, dialect, visiting);
        }
        if (catalogType.kind == WireTypeClassifier.Kind.SET
                || catalogType.kind == WireTypeClassifier.Kind.LIST) {
            return formatCollection(catalogType, dialect, visiting);
        }
        if (catalogType.kind == WireTypeClassifier.Kind.STRUCT
                || catalogType.kind == WireTypeClassifier.Kind.UNION) {
            return formatStruct(
                    declaredType, catalogType, visiting, preserveStructArguments);
        }
        return null;
    }

    private String formatOptional(
            WireTypeClassifier.CatalogType catalogType,
            ThriftAnnotationDialect dialect,
            Set<String> visiting) {
        List<? extends TypeMirror> arguments = catalogType.view.getTypeArguments();
        if (arguments.isEmpty()) {
            if ("java.lang.Integer".equals(catalogType.typeName)
                    || "java.lang.Long".equals(catalogType.typeName)
                    || "java.lang.Double".equals(catalogType.typeName)) {
                return catalogType.typeName;
            }
            return null;
        }
        return arguments.size() == 1 ? format(arguments.get(0), dialect, visiting, true) : null;
    }

    private String formatMap(
            WireTypeClassifier.CatalogType catalogType,
            ThriftAnnotationDialect dialect,
            Set<String> visiting) {
        List<? extends TypeMirror> arguments = catalogType.view.getTypeArguments();
        if (arguments.size() != 2) {
            return null;
        }
        String key = format(arguments.get(0), dialect, visiting, true);
        String value = format(arguments.get(1), dialect, visiting, true);
        return key == null || value == null ? null : "MAP<" + key + "," + value + ">";
    }

    private String formatCollection(
            WireTypeClassifier.CatalogType catalogType,
            ThriftAnnotationDialect dialect,
            Set<String> visiting) {
        List<? extends TypeMirror> arguments = catalogType.view.getTypeArguments();
        if (arguments.size() != 1) {
            return null;
        }
        String value = format(arguments.get(0), dialect, visiting, true);
        if (value == null) {
            return null;
        }
        return (catalogType.kind == WireTypeClassifier.Kind.SET ? "SET<" : "LIST<")
                + value + ">";
    }

    private String formatStruct(
            DeclaredType declaredType,
            WireTypeClassifier.CatalogType catalogType,
            Set<String> visiting,
            boolean preserveStructArguments) {
        List<? extends TypeMirror> arguments = declaredType.getTypeArguments();
        if (arguments.isEmpty() || !preserveStructArguments) {
            return "STRUCT:" + catalogType.typeName;
        }
        List<String> normalizedArguments = new ArrayList<String>();
        for (TypeMirror argument : arguments) {
            normalizedArguments.add(identityFormatter.javaTypeIdentity(argument, visiting, true));
        }
        return "STRUCT:" + catalogType.typeName + "<" + join(normalizedArguments) + ">";
    }

    private boolean isAnnotatedStructType(TypeMirror type) {
        if (type == null || type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        Element element = ((DeclaredType) type).asElement();
        if (!(element instanceof TypeElement)) {
            return false;
        }
        for (ThriftAnnotationDialect dialect : ThriftAnnotationDialect.values()) {
            if (ThriftAnnotations.has(element, dialect.thriftStruct())
                    || ThriftAnnotations.has(element, dialect.thriftUnion())) {
                return true;
            }
        }
        return false;
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
