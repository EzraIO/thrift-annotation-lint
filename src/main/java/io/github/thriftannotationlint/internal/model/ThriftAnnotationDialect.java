package io.github.thriftannotationlint.internal.model;

/** Qualified annotation names and runtime-specific behavior for a supported Thrift codec. */
public enum ThriftAnnotationDialect {
    FACEBOOK_SWIFT(
            "Facebook Swift", "com.facebook.swift.codec", ThriftRuntime.SWIFT),
    AIRLIFT_DRIFT(
            "Airlift Drift", "io.airlift.drift.annotations", ThriftRuntime.DRIFT),
    PRESTODB_DRIFT(
            "PrestoDB Drift", "com.facebook.drift.annotations", ThriftRuntime.DRIFT);

    private final String displayName;
    private final String annotationPackage;
    private final ThriftRuntime runtime;

    ThriftAnnotationDialect(
            String displayName,
            String annotationPackage,
            ThriftRuntime runtime) {
        this.displayName = displayName;
        this.annotationPackage = annotationPackage;
        this.runtime = runtime;
    }

    public String displayName() {
        return displayName;
    }

    public String runtimeName() {
        return runtime.displayName();
    }

    public ThriftRuntime runtime() {
        return runtime;
    }

    public String thriftStruct() {
        return annotation("ThriftStruct");
    }

    public String thriftField() {
        return annotation("ThriftField");
    }

    public String thriftConstructor() {
        return annotation("ThriftConstructor");
    }

    public String thriftUnion() {
        return annotation("ThriftUnion");
    }

    public String thriftUnionId() {
        return annotation("ThriftUnionId");
    }

    public String thriftEnum() {
        return annotation("ThriftEnum");
    }

    public String thriftEnumValue() {
        return annotation("ThriftEnumValue");
    }

    public String thriftEnumUnknownValue() {
        return runtime.enumPolicy().supportsUnknownValue()
                ? annotation("ThriftEnumUnknownValue")
                : null;
    }

    public String thriftIdlAnnotation() {
        return annotation("ThriftIdlAnnotation");
    }

    public String modelAnnotation(SwiftModel.Kind kind) {
        if (kind == SwiftModel.Kind.STRUCT) {
            return thriftStruct();
        }
        if (kind == SwiftModel.Kind.UNION) {
            return thriftUnion();
        }
        return thriftEnum();
    }

    public boolean ownsAnnotation(String annotationName) {
        return thriftStruct().equals(annotationName)
                || thriftField().equals(annotationName)
                || thriftConstructor().equals(annotationName)
                || thriftUnion().equals(annotationName)
                || thriftUnionId().equals(annotationName)
                || thriftEnum().equals(annotationName)
                || thriftEnumValue().equals(annotationName)
                || annotationName.equals(thriftEnumUnknownValue())
                || thriftIdlAnnotation().equals(annotationName);
    }

    private String annotation(String simpleName) {
        return annotationPackage + '.' + simpleName;
    }
}
