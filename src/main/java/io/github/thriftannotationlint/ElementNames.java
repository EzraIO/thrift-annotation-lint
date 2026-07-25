package io.github.thriftannotationlint;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import java.util.List;

/** Stable structural names for elements used in diagnostics and exact-type cache identities. */
final class ElementNames {
    private ElementNames() {
    }

    static String qualifiedMemberName(Element element) {
        if (element == null) {
            return "<no-element>";
        }
        if (element instanceof TypeElement) {
            return "TYPE:" + ((TypeElement) element).getQualifiedName();
        }
        if (element instanceof PackageElement) {
            return "PACKAGE:" + ((PackageElement) element).getQualifiedName();
        }

        Element owner = element.getEnclosingElement();
        String prefix = owner == null ? "" : qualifiedMemberName(owner) + "#";
        if (element instanceof ExecutableElement) {
            return prefix + executableKey((ExecutableElement) element);
        }
        if (element instanceof TypeParameterElement) {
            TypeParameterElement parameter = (TypeParameterElement) element;
            return prefix + "TYPE_PARAMETER:" + typeParameterIndex(parameter)
                    + ':' + parameter.getSimpleName() + ':' + boundsKey(parameter.getBounds());
        }
        if (element instanceof VariableElement && owner instanceof ExecutableElement) {
            ExecutableElement executable = (ExecutableElement) owner;
            VariableElement parameter = (VariableElement) element;
            return prefix + "PARAMETER:"
                    + elementIndex(executable.getParameters(), parameter)
                    + ':' + parameter.getSimpleName() + ':' + typeKey(parameter.asType(), false);
        }
        return prefix + element.getKind() + ":" + element.getSimpleName()
                + ':' + typeKey(element.asType(), false);
    }

    static String qualifiedTypeName(TypeElement type) {
        return type.getQualifiedName().toString();
    }

    private static String executableKey(ExecutableElement executable) {
        StringBuilder key = new StringBuilder("EXECUTABLE:");
        key.append(executable.getKind()).append(':').append(executable.getSimpleName()).append('(');
        for (VariableElement parameter : executable.getParameters()) {
            key.append(typeKey(parameter.asType(), true)).append(';');
        }
        key.append("):").append(typeKey(executable.getReturnType(), true));
        if (!executable.getTypeParameters().isEmpty()) {
            key.append('<');
            for (TypeParameterElement parameter : executable.getTypeParameters()) {
                key.append(boundsKey(parameter.getBounds())).append(';');
            }
            key.append('>');
        }
        return key.toString();
    }

    private static String boundsKey(List<? extends TypeMirror> bounds) {
        StringBuilder key = new StringBuilder();
        for (TypeMirror bound : bounds) {
            if (key.length() > 0) {
                key.append('&');
            }
            key.append(typeKey(bound, true));
        }
        return key.toString();
    }

    private static String typeKey(TypeMirror type, boolean eraseDeclaredArguments) {
        if (type == null) {
            return "<unknown>";
        }
        TypeKind kind = type.getKind();
        if (kind == TypeKind.ARRAY) {
            return '[' + typeKey(((ArrayType) type).getComponentType(), eraseDeclaredArguments);
        }
        if (kind == TypeKind.TYPEVAR) {
            TypeVariable variable = (TypeVariable) type;
            TypeMirror upperBound = variable.getUpperBound();
            return eraseDeclaredArguments
                    ? typeKey(firstBound(upperBound), true)
                    : "TYPE_VARIABLE:" + qualifiedMemberName(variable.asElement());
        }
        if (kind == TypeKind.DECLARED || kind == TypeKind.ERROR) {
            DeclaredType declared = (DeclaredType) type;
            Element declaredElement = declared.asElement();
            String rawName = declaredElement instanceof TypeElement
                    ? ((TypeElement) declaredElement).getQualifiedName().toString()
                    : declared.toString();
            if (eraseDeclaredArguments || declared.getTypeArguments().isEmpty()) {
                return rawName;
            }
            StringBuilder key = new StringBuilder(rawName).append('<');
            for (TypeMirror argument : declared.getTypeArguments()) {
                key.append(typeKey(argument, false)).append(';');
            }
            return key.append('>').toString();
        }
        if (kind == TypeKind.INTERSECTION) {
            return typeKey(firstBound(type), eraseDeclaredArguments);
        }
        return kind.name();
    }

    private static TypeMirror firstBound(TypeMirror type) {
        if (type != null && type.getKind() == TypeKind.INTERSECTION) {
            List<? extends TypeMirror> bounds = ((IntersectionType) type).getBounds();
            return bounds.isEmpty() ? null : bounds.get(0);
        }
        return type;
    }

    private static int elementIndex(
            List<? extends Element> elements,
            Element target) {
        for (int index = 0; index < elements.size(); index++) {
            if (elements.get(index).equals(target)) {
                return index;
            }
        }
        return -1;
    }

    private static int typeParameterIndex(TypeParameterElement parameter) {
        Element generic = parameter.getGenericElement();
        if (generic instanceof ExecutableElement) {
            return elementIndex(
                    ((ExecutableElement) generic).getTypeParameters(), parameter);
        }
        if (generic instanceof TypeElement) {
            return elementIndex(((TypeElement) generic).getTypeParameters(), parameter);
        }
        return -1;
    }
}
