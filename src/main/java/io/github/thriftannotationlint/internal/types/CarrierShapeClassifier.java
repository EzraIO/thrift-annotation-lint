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
            return "DEFERRED";
        }
        if (type.getKind() == TypeKind.TYPEVAR) {
            TypeVariable variable = (TypeVariable) type;
            if (identityFormatter.isModelTypeVariable(variable)) {
                return "DEFERRED";
            }
            return classify(variable.getUpperBound(), dialect, visiting);
        }
        if (type.getKind() == TypeKind.WILDCARD) {
            return classify(((WildcardType) type).getExtendsBound(), dialect, visiting);
        }
        if (type.getKind() == TypeKind.INTERSECTION) {
            List<? extends TypeMirror> bounds = ((IntersectionType) type).getBounds();
            return bounds.isEmpty() ? "DEFERRED" : classify(bounds.get(0), dialect, visiting);
        }
        if (type.getKind() != TypeKind.DECLARED) {
            return "VALUE";
        }
        String visitKey = "CARRIER:" + dialect + ":" + type;
        if (!visiting.add(visitKey)) {
            return "DEFERRED";
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
            return arguments.size() == 1
                    ? "OPTIONAL<" + classify(arguments.get(0), dialect, visiting) + ">"
                    : "OPTIONAL_PRIMITIVE:" + catalogType.typeName;
        }
        if (catalogType.kind == WireTypeClassifier.Kind.MAP) {
            return arguments.size() == 2
                    ? "MAP<" + classify(arguments.get(0), dialect, visiting)
                    + "," + classify(arguments.get(1), dialect, visiting) + ">"
                    : "VALUE";
        }
        if (catalogType.kind == WireTypeClassifier.Kind.SET
                || catalogType.kind == WireTypeClassifier.Kind.LIST) {
            return arguments.size() == 1
                    ? catalogType.kind.name() + "<"
                    + classify(arguments.get(0), dialect, visiting) + ">"
                    : "VALUE";
        }
        return "VALUE";
    }
}
