package io.github.thriftannotationlint;

/** Stable diagnostic identifiers exposed by the annotation processor. */
enum DiagnosticCode {
    MODEL_DECLARATION("AW1001", false),
    MISSING_FIELD_ID("AW2001", false),
    DUPLICATE_FIELD_ID("AW2002", false),
    CONFLICTING_FIELD_ID("AW2003", false),
    CONFLICTING_FIELD_NAME("AW2004", false),
    CONFLICTING_REQUIREDNESS("AW2005", false),
    INVALID_LEGACY_ID("AW2006", false),
    CONFLICTING_IDL_ANNOTATIONS("AW2007", false),
    MISSING_ACCESS_PATH("AW3001", false),
    MULTIPLE_THRIFT_CONSTRUCTORS("AW3002", false),
    INVALID_METHOD_OR_CONSTRUCTOR("AW3003", false),
    INVALID_MEMBER_MODIFIERS("AW3004", false),
    INVALID_BUILDER("AW3005", false),
    UNSUPPORTED_JAVA_TYPE("AW4001", false),
    CONFLICTING_JAVA_TYPES("AW4002", false),
    INVALID_RECURSIVE_FIELD("AW4003", false),
    INVALID_UNION_ID("AW5001", false),
    INVALID_UNION_CONSTRUCTOR("AW5002", false),
    INVALID_UNION_REQUIREDNESS("AW5003", false),
    UNSAFE_UNION_FIELD_ID("AW5004", false),
    INVALID_ENUM_VALUE_METHOD("AW6001", false),
    INVALID_PROCESSOR_OPTION("AW9001", true),
    INTERNAL_PROCESSOR_FAILURE("AW9002", true),
    VALIDATION_LIMIT_EXCEEDED("AW9003", true);

    private final String id;
    private final boolean alwaysError;

    DiagnosticCode(String id, boolean alwaysError) {
        this.id = id;
        this.alwaysError = alwaysError;
    }

    String id() {
        return id;
    }

    boolean isAlwaysError() {
        return alwaysError;
    }

    int reportingPriority() {
        if (alwaysError) {
            return 0;
        }
        // Missing IDs and access paths are commonly consequences of a more precise declaration
        // or conflict finding. Report the root cause first when old javac versions collapse
        // diagnostics that share a preferred source position.
        if (this == MISSING_FIELD_ID || this == MISSING_ACCESS_PATH) {
            return 2;
        }
        return 1;
    }
}
