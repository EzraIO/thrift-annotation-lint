package io.github.thriftannotationlint.internal.types;

import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;

import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Preserves Java carrier wrappers that wire normalization intentionally collapses. */
final class CarrierShapeClassifier {
    private final WireTypeClassifier classifier;
    private final JavaTypeIdentityFormatter identityFormatter;

    CarrierShapeClassifier(
            WireTypeClassifier classifier,
            JavaTypeIdentityFormatter identityFormatter) {
        this.classifier = classifier;
        this.identityFormatter = identityFormatter;
    }

    String classify(TypeMirror type, ThriftAnnotationDialect dialect) {
        return classify(type, dialect, new HashSet<String>());
    }

    private String classify(
            TypeMirror type,
            ThriftAnnotationDialect dialect,
        Set<String> visiting) {
        if (type == null || type.getKind() == TypeKind.ERROR) {
            return WireTypeTokens.DEFERRED;
        }
        if (type.getKind() == TypeKind.TYPEVAR) {
            TypeVariable variable = (TypeVariable) type;
            if (identityFormatter.isModelTypeVariable(variable)) {
                return WireTypeTokens.DEFERRED;
            }
            return classify(variable.getUpperBound(), dialect, visiting);
        }
        if (type.getKind() == TypeKind.WILDCARD) {
            return classify(((WildcardType) type).getExtendsBound(), dialect, visiting);
        }
        if (type.getKind() == TypeKind.INTERSECTION) {
            List<? extends TypeMirror> bounds = ((IntersectionType) type).getBounds();
            return bounds.isEmpty()
                    ? WireTypeTokens.DEFERRED
                    : classify(bounds.get(0), dialect, visiting);
        }
        if (type.getKind() != TypeKind.DECLARED) {
            return WireTypeTokens.VALUE;
        }
        String visitKey = WireTypeTokens.CARRIER_VISIT_PREFIX + dialect + ":" + type;
        if (!visiting.add(visitKey)) {
            return WireTypeTokens.DEFERRED;
        }
        try {
            return classifyDeclared((DeclaredType) type, dialect, visiting);
        }
        finally {
            visiting.remove(visitKey);
        }
    }

    private String classifyDeclared(
            DeclaredType type,
            ThriftAnnotationDialect dialect,
            Set<String> visiting) {
        WireTypeClassifier.CatalogType catalogType = classifier.classify(type, dialect);
        List<? extends TypeMirror> arguments = catalogType.view == null
                ? java.util.Collections.<TypeMirror>emptyList()
                : catalogType.view.getTypeArguments();
        if (catalogType.kind == WireTypeClassifier.Kind.OPTIONAL) {
            return arguments.size() == GenericTypeShape.VALUE_ARGUMENT_COUNT
                    ? WireTypeTokens.OPTIONAL_PREFIX + classify(
                    arguments.get(GenericTypeShape.VALUE_ARGUMENT_INDEX), dialect, visiting) + ">"
                    : WireTypeTokens.OPTIONAL_PRIMITIVE_PREFIX + catalogType.typeName;
        }
        if (catalogType.kind == WireTypeClassifier.Kind.MAP) {
            return arguments.size() == GenericTypeShape.MAP_ARGUMENT_COUNT
                    ? WireTypeTokens.MAP_PREFIX + classify(
                    arguments.get(GenericTypeShape.MAP_KEY_ARGUMENT_INDEX), dialect, visiting)
                    + "," + classify(
                    arguments.get(GenericTypeShape.MAP_VALUE_ARGUMENT_INDEX), dialect, visiting)
                    + ">"
                    : WireTypeTokens.VALUE;
        }
        if (catalogType.kind == WireTypeClassifier.Kind.SET
                || catalogType.kind == WireTypeClassifier.Kind.LIST) {
            return arguments.size() == GenericTypeShape.VALUE_ARGUMENT_COUNT
                    ? catalogType.kind.name() + "<"
                    + classify(
                    arguments.get(GenericTypeShape.VALUE_ARGUMENT_INDEX), dialect, visiting) + ">"
                    : WireTypeTokens.VALUE;
        }
        return WireTypeTokens.VALUE;
    }
}
