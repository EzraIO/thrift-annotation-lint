package io.github.thriftannotationlint.internal.extract;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;

/** Mirrors Swift's ReflectionHelper field-name extraction without loading user classes. */
public final class SwiftMemberNames {
    private SwiftMemberNames() {
    }

    public static String extractedFieldName(Element member) {
        if (member instanceof ExecutableElement) {
            return extractedFieldName(member.getSimpleName().toString());
        }
        return member.getSimpleName().toString();
    }

    static String extractedFieldName(String methodName) {
        if ((methodName.startsWith("get") || methodName.startsWith("set"))
                && methodName.length() > 3) {
            return Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
        }
        if (methodName.startsWith("is") && methodName.length() > 2) {
            return Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
        }
        return methodName;
    }
}
