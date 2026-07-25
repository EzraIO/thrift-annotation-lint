package io.github.thriftannotationlint;

enum ProcessorMode {
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
