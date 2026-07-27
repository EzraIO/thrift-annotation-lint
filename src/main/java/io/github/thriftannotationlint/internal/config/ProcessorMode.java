package io.github.thriftannotationlint.internal.config;

public enum ProcessorMode {
    STRICT,
    WARNING;

    static ProcessorMode parse(String value) {
        if (value == null || value.isEmpty() || "strict".equals(value)) {
            return STRICT;
        }
        if ("warning".equals(value)) {
            return WARNING;
        }
        return null;
    }
}
