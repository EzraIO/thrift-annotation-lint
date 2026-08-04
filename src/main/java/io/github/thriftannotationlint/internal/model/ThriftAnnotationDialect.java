package io.github.thriftannotationlint.internal.model;

/** Qualified annotation names and runtime-specific behavior for a supported Thrift codec. */
public enum ThriftAnnotationDialect {
    FACEBOOK_SWIFT("Facebook Swift", "Swift", "com.facebook.swift.codec", false),
    AIRLIFT_DRIFT("Airlift Drift", "Drift", "io.airlift.drift.annotations", true),
    PRESTODB_DRIFT("PrestoDB Drift", "Drift", "com.facebook.drift.annotations", true);

    private final String displayName;
    private final String runtimeName;
    private final String annotationPackage;
    private final boolean drift;

    ThriftAnnotationDialect(
            String displayName,
            String runtimeName,
            String annotationPackage,
            boolean drift) {
        this.displayName = displayName;
        this.runtimeName = runtimeName;
        this.annotationPackage = annotationPackage;
        this.drift = drift;
    }

    public String displayName() {
        return displayName;
    }

    public String runtimeName() {
        return runtimeName;
    }

    public boolean isDrift() {
        return drift;
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
        return isDrift() ? annotation("ThriftEnumUnknownValue") : null;
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
