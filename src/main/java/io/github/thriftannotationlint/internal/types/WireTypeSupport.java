package io.github.thriftannotationlint.internal.types;

import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;

import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Recursively determines whether a Java type has a supported wire representation. */
final class WireTypeSupport {
    private final WireTypeClassifier classifier;
    private final JavaTypeIdentityFormatter identityFormatter;

    WireTypeSupport(
            WireTypeClassifier classifier,
            JavaTypeIdentityFormatter identityFormatter) {
        this.classifier = classifier;
        this.identityFormatter = identityFormatter;
    }

    boolean isSupported(TypeMirror type) {
        return isSupported(
                type, ThriftAnnotationDialect.FACEBOOK_SWIFT, new HashSet<String>());
    }

    boolean isSupported(TypeMirror type, ThriftAnnotationDialect dialect) {
        return isSupported(type, dialect, new HashSet<String>());
    }

    private boolean isSupported(
            TypeMirror type,
            ThriftAnnotationDialect dialect,
            Set<String> visiting) {
        if (type == null) {
            return false;
        }
        if (type.getKind() == TypeKind.ERROR) {
            return true;
        }
        if (isSupportedPrimitive(type.getKind())) {
            return true;
        }
        if (type.getKind() == TypeKind.ARRAY) {
            return isSupportedArray((ArrayType) type);
        }
        if (type.getKind() == TypeKind.TYPEVAR) {
            TypeVariable variable = (TypeVariable) type;
            return identityFormatter.isModelTypeVariable(variable)
                    || variable.getUpperBound() != null
                    && isSupported(variable.getUpperBound(), dialect, visiting);
        }
        if (type.getKind() == TypeKind.WILDCARD) {
            TypeMirror upperBound = ((WildcardType) type).getExtendsBound();
            return upperBound != null && isSupported(upperBound, dialect, visiting);
        }
        if (type.getKind() == TypeKind.INTERSECTION) {
            List<? extends TypeMirror> bounds = ((IntersectionType) type).getBounds();
            return !bounds.isEmpty() && isSupported(bounds.get(0), dialect, visiting);
        }
        return type.getKind() == TypeKind.DECLARED
                && isSupportedDeclared((DeclaredType) type, dialect, visiting);
    }

    private boolean isSupportedDeclared(
            DeclaredType type,
            ThriftAnnotationDialect dialect,
            Set<String> visiting) {
        String visitKey = WireTypeTokens.SUPPORTED_VISIT_PREFIX + type;
        if (!visiting.add(visitKey)) {
            return false;
        }
        try {
            WireTypeClassifier.CatalogType catalogType = classifier.classify(type, dialect);
            if (isScalarOrModel(catalogType.kind)) {
                return true;
            }
            if (catalogType.kind == WireTypeClassifier.Kind.OPTIONAL) {
                return isSupportedOptional(catalogType, dialect, visiting);
            }
            return classifier.isContainerKind(catalogType.kind)
                    && hasSupportedContainerArguments(catalogType, dialect, visiting);
        }
        finally {
            visiting.remove(visitKey);
        }
    }

    private boolean isScalarOrModel(WireTypeClassifier.Kind kind) {
        return kind == WireTypeClassifier.Kind.BOXED
                || kind == WireTypeClassifier.Kind.STRING
                || kind == WireTypeClassifier.Kind.BINARY
                || kind == WireTypeClassifier.Kind.ENUM
                || kind == WireTypeClassifier.Kind.STRUCT
                || kind == WireTypeClassifier.Kind.UNION;
    }

    private boolean isSupportedOptional(
            WireTypeClassifier.CatalogType catalogType,
            ThriftAnnotationDialect dialect,
            Set<String> visiting) {
        List<? extends TypeMirror> arguments = catalogType.view.getTypeArguments();
        if (arguments.isEmpty()) {
            return !catalogType.typeName.startsWith("java.util.Optional");
        }
        return arguments.size() == GenericTypeShape.VALUE_ARGUMENT_COUNT
                && isSupported(arguments.get(GenericTypeShape.VALUE_ARGUMENT_INDEX),
                dialect, visiting);
    }

    private boolean hasSupportedContainerArguments(
            WireTypeClassifier.CatalogType catalogType,
            ThriftAnnotationDialect dialect,
            Set<String> visiting) {
        List<? extends TypeMirror> arguments = catalogType.view.getTypeArguments();
        int expectedArguments = catalogType.kind == WireTypeClassifier.Kind.MAP
                ? GenericTypeShape.MAP_ARGUMENT_COUNT
                : GenericTypeShape.VALUE_ARGUMENT_COUNT;
        if (arguments.size() != expectedArguments) {
            return false;
        }
        for (TypeMirror argument : arguments) {
            if (!isSupported(argument, dialect, visiting)) {
                return false;
            }
        }
        return true;
    }

    static boolean isSupportedArray(ArrayType array) {
        TypeKind component = array.getComponentType().getKind();
        return component == TypeKind.BOOLEAN
                || component == TypeKind.BYTE
                || component == TypeKind.SHORT
                || component == TypeKind.INT
                || component == TypeKind.LONG
                || component == TypeKind.DOUBLE;
    }

    static boolean isSupportedPrimitive(TypeKind kind) {
        return kind == TypeKind.BOOLEAN
                || kind == TypeKind.BYTE
                || kind == TypeKind.SHORT
                || kind == TypeKind.INT
                || kind == TypeKind.LONG
                || kind == TypeKind.FLOAT
                || kind == TypeKind.DOUBLE;
    }
}
