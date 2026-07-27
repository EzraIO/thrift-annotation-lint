package io.github.thriftannotationlint.internal.model;

/** Qualified annotation names and runtime-specific behavior for a supported Thrift codec. */
public enum ThriftAnnotationDialect {
    FACEBOOK_SWIFT("Facebook Swift", "Swift", "com.facebook.swift.codec"),
    AIRLIFT_DRIFT("Airlift Drift", "Drift", "io.airlift.drift.annotations");

    private final String displayName;
    private final String runtimeName;
    private final String annotationPackage;

    ThriftAnnotationDialect(String displayName, String runtimeName, String annotationPackage) {
        this.displayName = displayName;
        this.runtimeName = runtimeName;
        this.annotationPackage = annotationPackage;
    }

    public String displayName() {
        return displayName;
    }

    public String runtimeName() {
        return runtimeName;
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
        return this == AIRLIFT_DRIFT ? annotation("ThriftEnumUnknownValue") : null;
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
