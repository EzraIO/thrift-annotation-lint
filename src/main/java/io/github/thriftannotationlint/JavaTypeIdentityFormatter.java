package io.github.thriftannotationlint;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Formats the stable Java type identities used by exact-model validation. */
final class JavaTypeIdentityFormatter {
    String exactJavaTypeIdentity(TypeMirror type) {
        return javaTypeIdentity(type, new HashSet<String>(), false);
    }

    String javaTypeIdentity(TypeMirror type, boolean deferModelTypeVariables) {
        return javaTypeIdentity(
                type, new HashSet<String>(), deferModelTypeVariables);
    }

    String javaTypeIdentity(
            TypeMirror type,
            Set<String> visiting,
            boolean deferModelTypeVariables) {
        if (type == null) {
            return "JAVA:<unknown>";
        }
        if (type.getKind() == TypeKind.TYPEVAR) {
            return deferModelTypeVariables && isModelTypeVariable(type)
                    ? "DEFERRED_TYPE_VARIABLE"
                    : typeVariableIdentity(type);
        }
        if (type.getKind() == TypeKind.ARRAY) {
            return "JAVA_ARRAY<"
                    + javaTypeIdentity(
                    ((ArrayType) type).getComponentType(),
                    visiting,
                    deferModelTypeVariables) + ">";
        }
        if (type.getKind() == TypeKind.WILDCARD) {
            WildcardType wildcard = (WildcardType) type;
            if (wildcard.getExtendsBound() != null) {
                return "JAVA_EXTENDS<"
                        + javaTypeIdentity(
                        wildcard.getExtendsBound(), visiting, deferModelTypeVariables) + ">";
            }
            if (wildcard.getSuperBound() != null) {
                return "JAVA_SUPER<"
                        + javaTypeIdentity(
                        wildcard.getSuperBound(), visiting, deferModelTypeVariables) + ">";
            }
            return "JAVA_WILDCARD";
        }
        if (type.getKind() != TypeKind.DECLARED) {
            return "JAVA:" + type.getKind().name();
        }

        DeclaredType declared = (DeclaredType) type;
        Element element = declared.asElement();
        String name = element instanceof TypeElement
                ? ((TypeElement) element).getQualifiedName().toString()
                : type.toString();
        String visitKey = "JAVA_IDENTITY:" + type;
        if (!visiting.add(visitKey)) {
            return "JAVA:" + name;
        }
        try {
            List<String> arguments = new ArrayList<String>();
            TypeMirror enclosing = declared.getEnclosingType();
            if (enclosing != null && enclosing.getKind() == TypeKind.DECLARED) {
                arguments.add("JAVA_OWNER<" + javaTypeIdentity(
                        enclosing, visiting, deferModelTypeVariables) + ">");
            }
            if (declared.getTypeArguments().isEmpty() && arguments.isEmpty()) {
                return "JAVA:" + name;
            }
            for (TypeMirror argument : declared.getTypeArguments()) {
                arguments.add(javaTypeIdentity(argument, visiting, deferModelTypeVariables));
            }
            return "JAVA:" + name + "<" + join(arguments) + ">";
        }
        finally {
            visiting.remove(visitKey);
        }
    }

    boolean isModelTypeVariable(TypeMirror type) {
        if (type == null || type.getKind() != TypeKind.TYPEVAR) {
            return false;
        }
        Element parameter = ((TypeVariable) type).asElement();
        if (!(parameter instanceof TypeParameterElement)) {
            return false;
        }
        Element genericElement = ((TypeParameterElement) parameter).getGenericElement();
        if (!(genericElement instanceof TypeElement)) {
            return false;
        }
        // JDK 8's asMemberOf can associate an executable type variable with the enclosing type.
        // A true model variable must also be one of that TypeElement's declared parameters.
        for (TypeParameterElement declared
                : ((TypeElement) genericElement).getTypeParameters()) {
            if (declared.equals(parameter)) {
                return true;
            }
        }
        return false;
    }

    String typeVariableIdentity(TypeMirror type) {
        Element parameter = ((TypeVariable) type).asElement();
        if (!(parameter instanceof TypeParameterElement)) {
            return "EXECUTABLE_TYPE_VARIABLE:" + type;
        }
        return "EXECUTABLE_TYPE_VARIABLE:"
                + ElementNames.qualifiedMemberName(parameter);
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
