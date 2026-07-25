package io.github.thriftannotationlint;

import java.util.Map;

/** Immutable, side-effect-free processor option parsing. */
final class ProcessorOptions {
    static final int DEFAULT_MAX_EXACT_MODELS = 512;

    private final ProcessorMode mode;
    private final int maxExactModels;
    private final String validationError;

    private ProcessorOptions(
            ProcessorMode mode,
            int maxExactModels,
            String validationError) {
        this.mode = mode;
        this.maxExactModels = maxExactModels;
        this.validationError = validationError;
    }

    static ProcessorOptions parse(Map<String, String> values) {
        String configuredMode = values.get(ThriftAnnotationLintProcessor.MODE_OPTION);
        ProcessorMode parsedMode = ProcessorMode.parse(configuredMode);
        String modeError = null;
        if (parsedMode == null) {
            parsedMode = ProcessorMode.STRICT;
            modeError = "Unsupported -A" + ThriftAnnotationLintProcessor.MODE_OPTION
                    + " value; expected 'strict' or 'warning'.";
        }

        int parsedLimit = DEFAULT_MAX_EXACT_MODELS;
        String limitError = null;
        String configuredLimit = values.get(ThriftAnnotationLintProcessor.MAX_EXACT_MODELS_OPTION);
        if (configuredLimit != null && !configuredLimit.isEmpty()) {
            try {
                int candidateLimit = Integer.parseInt(configuredLimit);
                if (candidateLimit <= 0) {
                    limitError = "Unsupported -A" + ThriftAnnotationLintProcessor.MAX_EXACT_MODELS_OPTION
                            + " value; expected a positive integer.";
                }
                else {
                    parsedLimit = candidateLimit;
                }
            }
            catch (NumberFormatException ignored) {
                limitError = "Unsupported -A" + ThriftAnnotationLintProcessor.MAX_EXACT_MODELS_OPTION
                        + " value; expected a positive integer.";
            }
        }
        return new ProcessorOptions(
                parsedMode,
                parsedLimit,
                modeError == null ? limitError : modeError);
    }

    ProcessorMode mode() {
        return mode;
    }

    int maxExactModels() {
        return maxExactModels;
    }

    String validationError() {
        return validationError;
    }
}
