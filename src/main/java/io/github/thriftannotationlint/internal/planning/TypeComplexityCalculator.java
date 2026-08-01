package io.github.thriftannotationlint.internal.planning;

import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import java.util.LinkedHashSet;
import java.util.Set;

/** Calculates the deterministic structural complexity used by generic-growth detection. */
final class TypeComplexityCalculator {
    int measure(TypeMirror type) {
        return measure(type, new LinkedHashSet<String>());
    }

    private int measure(TypeMirror type, Set<String> visiting) {
        if (type == null || type.getKind() == TypeKind.NONE) {
            return 0;
        }
        String key = type.getKind() + ":" + type;
        if (!visiting.add(key)) {
            return 0;
        }
        try {
            int complexity = 1;
            if (type.getKind() == TypeKind.DECLARED) {
                DeclaredType declared = (DeclaredType) type;
                complexity += measure(declared.getEnclosingType(), visiting);
                for (TypeMirror argument : declared.getTypeArguments()) {
                    complexity += measure(argument, visiting);
                }
            }
            else if (type.getKind() == TypeKind.ARRAY) {
                complexity += measure(((ArrayType) type).getComponentType(), visiting);
            }
            else if (type.getKind() == TypeKind.WILDCARD) {
                WildcardType wildcard = (WildcardType) type;
                complexity += measure(wildcard.getExtendsBound(), visiting);
                complexity += measure(wildcard.getSuperBound(), visiting);
            }
            else if (type.getKind() == TypeKind.INTERSECTION) {
                for (TypeMirror bound : ((IntersectionType) type).getBounds()) {
                    complexity += measure(bound, visiting);
                }
            }
            return complexity;
        }
        finally {
            visiting.remove(key);
        }
    }
}
