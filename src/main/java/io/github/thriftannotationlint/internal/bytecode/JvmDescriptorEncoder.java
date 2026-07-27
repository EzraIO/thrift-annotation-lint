package io.github.thriftannotationlint.internal.bytecode;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/** Encodes source-model types using the erased descriptors used by JVM method lookup. */
final class JvmDescriptorEncoder {
    private final Elements elements;
    private final Types types;

    JvmDescriptorEncoder(Elements elements, Types types) {
        this.elements = elements;
        this.types = types;
    }

    String parameterDescriptor(ExecutableElement executable) {
        ExecutableType executableType = (ExecutableType) executable.asType();
        StringBuilder descriptor = new StringBuilder("(");
        for (TypeMirror parameter : executableType.getParameterTypes()) {
            descriptor.append(descriptor(parameter));
        }
        return descriptor.append(')').toString();
    }

    String descriptor(TypeMirror type) {
        TypeKind kind = type.getKind();
        if (kind == TypeKind.BOOLEAN) {
            return "Z";
        }
        if (kind == TypeKind.BYTE) {
            return "B";
        }
        if (kind == TypeKind.CHAR) {
            return "C";
        }
        if (kind == TypeKind.SHORT) {
            return "S";
        }
        if (kind == TypeKind.INT) {
            return "I";
        }
        if (kind == TypeKind.LONG) {
            return "J";
        }
        if (kind == TypeKind.FLOAT) {
            return "F";
        }
        if (kind == TypeKind.DOUBLE) {
            return "D";
        }
        if (kind == TypeKind.VOID) {
            return "V";
        }
        if (kind == TypeKind.ARRAY) {
            return "[" + descriptor(((ArrayType) type).getComponentType());
        }

        TypeMirror erased = types.erasure(type);
        Element element = erased.getKind() == TypeKind.DECLARED
                ? ((DeclaredType) erased).asElement()
                : null;
        if (element instanceof TypeElement) {
            return "L" + elements.getBinaryName((TypeElement) element)
                    .toString().replace('.', '/') + ";";
        }
        // This is the same fail-closed erasure fallback used by the previous inline encoder.
        return "Ljava/lang/Object;";
    }
}
