package io.github.thriftannotationlint.internal.types;

/** Stable internal tokens shared by wire-shape producers and consumers. */
final class WireTypeTokens {
    static final String ERROR = "ERROR";
    static final String DEFERRED = "DEFERRED";
    static final String DEFERRED_TYPE_VARIABLE = "DEFERRED_TYPE_VARIABLE";
    static final String VALUE = "VALUE";

    static final String JAVA_PREFIX = "JAVA:";
    static final String JAVA_ARRAY_PREFIX = "JAVA_ARRAY<";
    static final String JAVA_EXTENDS_PREFIX = "JAVA_EXTENDS<";
    static final String JAVA_SUPER_PREFIX = "JAVA_SUPER<";
    static final String JAVA_OWNER_PREFIX = "JAVA_OWNER<";
    static final String JAVA_ARRAY_KIND = "JAVA_ARRAY";
    static final String JAVA_EXTENDS_KIND = "JAVA_EXTENDS";
    static final String JAVA_SUPER_KIND = "JAVA_SUPER";
    static final String JAVA_OWNER_KIND = "JAVA_OWNER";
    static final String JAVA_WILDCARD = "JAVA_WILDCARD";
    static final String EXECUTABLE_TYPE_VARIABLE_PREFIX = "EXECUTABLE_TYPE_VARIABLE:";

    static final String LIST_PREFIX = "LIST<";
    static final String SET_PREFIX = "SET<";
    static final String MAP_PREFIX = "MAP<";
    static final String OPTIONAL_PREFIX = "OPTIONAL<";
    static final String OPTIONAL_PRIMITIVE_PREFIX = "OPTIONAL_PRIMITIVE:";
    static final String LIST_KIND = "LIST";
    static final String SET_KIND = "SET";
    static final String MAP_KIND = "MAP";
    static final String OPTIONAL_KIND = "OPTIONAL";
    static final String STRUCT_PREFIX = "STRUCT:";
    static final String ENUM_PREFIX = "ENUM:";
    static final String BINARY_TYPE = "BINARY:java.nio.ByteBuffer";

    static final String JAVA_IDENTITY_VISIT_PREFIX = "JAVA_IDENTITY:";
    static final String SUPPORTED_VISIT_PREFIX = "SUPPORTED:";
    static final String CARRIER_VISIT_PREFIX = "CARRIER:";
    static final String NORMALIZED_VISIT_PREFIX = "NORMALIZED:";
    static final String READ_VISIT_PREFIX = "READ:";
    static final String WRITE_VISIT_PREFIX = "WRITE:";

    private WireTypeTokens() {
    }
}
