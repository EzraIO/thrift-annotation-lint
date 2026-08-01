package io.github.thriftannotationlint.internal.validation;

/** Shared diagnostic prefixes; callers append the rule-specific detail verbatim. */
final class ValidationText {
    private ValidationText() {
    }

    static String model(String modelName) {
        return "Thrift model '" + modelName + "'";
    }

    static String modelField(String modelName, String fieldName) {
        return "Thrift model '" + modelName + "' field '" + fieldName + "'";
    }

    static String unionField(String modelName, String fieldName) {
        return "Thrift union '" + modelName + "' field '" + fieldName + "'";
    }
}
