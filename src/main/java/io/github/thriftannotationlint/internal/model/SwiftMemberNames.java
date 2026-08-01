package io.github.thriftannotationlint.internal.model;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;

/** Mirrors Swift's ReflectionHelper field-name extraction without loading user classes. */
public final class SwiftMemberNames {
    private static final String GETTER_PREFIX = "get";
    private static final String SETTER_PREFIX = "set";
    private static final String BOOLEAN_GETTER_PREFIX = "is";

    private SwiftMemberNames() {
    }

    public static String extractedFieldName(Element member) {
        if (member instanceof ExecutableElement) {
            return extractedFieldName(member.getSimpleName().toString());
        }
        return member.getSimpleName().toString();
    }

    public static String extractedFieldName(String methodName) {
        if (methodName.startsWith(GETTER_PREFIX) || methodName.startsWith(SETTER_PREFIX)) {
            return stripAccessorPrefix(methodName, GETTER_PREFIX.length());
        }
        if (methodName.startsWith(BOOLEAN_GETTER_PREFIX)) {
            return stripAccessorPrefix(methodName, BOOLEAN_GETTER_PREFIX.length());
        }
        return methodName;
    }

    private static String stripAccessorPrefix(String methodName, int prefixLength) {
        if (methodName.length() <= prefixLength) {
            return methodName;
        }
        return Character.toLowerCase(methodName.charAt(prefixLength))
                + methodName.substring(prefixLength + 1);
    }
}
