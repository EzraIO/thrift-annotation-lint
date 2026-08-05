package io.github.thriftannotationlint.internal.model;

/** Typed codec policies shared by annotation dialects backed by the same runtime family. */
public enum ThriftRuntime {
    SWIFT(
            "Swift",
            "swift.recursive_reference",
            false,
            EnumPolicy.SWIFT,
            ParameterNameStrategy.SWIFT_PARANAMER),
    DRIFT(
            "Drift",
            "drift.recursive_reference",
            true,
            EnumPolicy.DRIFT,
            ParameterNameStrategy.DRIFT_PARAMETER_NAMES);

    private final String displayName;
    private final String recursiveReferenceIdlKey;
    private final boolean optionalCarriers;
    private final EnumPolicy enumPolicy;
    private final ParameterNameStrategy parameterNameStrategy;

    ThriftRuntime(
            String displayName,
            String recursiveReferenceIdlKey,
            boolean optionalCarriers,
            EnumPolicy enumPolicy,
            ParameterNameStrategy parameterNameStrategy) {
        this.displayName = displayName;
        this.recursiveReferenceIdlKey = recursiveReferenceIdlKey;
        this.optionalCarriers = optionalCarriers;
        this.enumPolicy = enumPolicy;
        this.parameterNameStrategy = parameterNameStrategy;
    }

    public String displayName() {
        return displayName;
    }

    public boolean supportsOptionalCarriers() {
        return optionalCarriers;
    }

    public String recursiveReferenceIdlKey() {
        return recursiveReferenceIdlKey;
    }

    public EnumPolicy enumPolicy() {
        return enumPolicy;
    }

    public ParameterNameStrategy parameterNameStrategy() {
        return parameterNameStrategy;
    }

    public enum EnumPolicy {
        SWIFT(false, false, false),
        DRIFT(true, true, true);

        private final boolean modelAnnotationRequired;
        private final boolean valueMethodRequired;
        private final boolean unknownValueSupported;

        EnumPolicy(
                boolean modelAnnotationRequired,
                boolean valueMethodRequired,
                boolean unknownValueSupported) {
            this.modelAnnotationRequired = modelAnnotationRequired;
            this.valueMethodRequired = valueMethodRequired;
            this.unknownValueSupported = unknownValueSupported;
        }

        public boolean requiresModelAnnotation() {
            return modelAnnotationRequired;
        }

        public boolean requiresValueMethod() {
            return valueMethodRequired;
        }

        public boolean supportsUnknownValue() {
            return unknownValueSupported;
        }
    }

    public enum ParameterNameStrategy {
        SWIFT_PARANAMER(false, true, false),
        DRIFT_PARAMETER_NAMES(true, false, true);

        private final boolean methodParametersPreferred;
        private final boolean javaxInjectNamedSupported;
        private final boolean invalidBytecodeFallback;

        ParameterNameStrategy(
                boolean methodParametersPreferred,
                boolean javaxInjectNamedSupported,
                boolean invalidBytecodeFallback) {
            this.methodParametersPreferred = methodParametersPreferred;
            this.javaxInjectNamedSupported = javaxInjectNamedSupported;
            this.invalidBytecodeFallback = invalidBytecodeFallback;
        }

        public boolean prefersMethodParameters() {
            return methodParametersPreferred;
        }

        public boolean supportsJavaxInjectNamed() {
            return javaxInjectNamedSupported;
        }

        public boolean fallsBackFromInvalidBytecode() {
            return invalidBytecodeFallback;
        }
    }
}
