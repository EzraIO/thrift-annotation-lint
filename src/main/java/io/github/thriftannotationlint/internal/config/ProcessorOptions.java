package io.github.thriftannotationlint.internal.config;

import java.util.Map;

/** Immutable, side-effect-free processor option parsing. */
public final class ProcessorOptions {
    public static final String MODE_OPTION = "thrift.annotation.lint.mode";
    public static final String MAX_EXACT_MODELS_OPTION =
            "thrift.annotation.lint.maxExactModels";
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

    public static ProcessorOptions parse(Map<String, String> values) {
        String configuredMode = values.get(MODE_OPTION);
        ProcessorMode parsedMode = ProcessorMode.parse(configuredMode);
        String modeError = null;
        if (parsedMode == null) {
            parsedMode = ProcessorMode.STRICT;
            modeError = "Unsupported -A" + MODE_OPTION
                    + " value; expected 'strict' or 'warning'.";
        }

        int parsedLimit = DEFAULT_MAX_EXACT_MODELS;
        String limitError = null;
        String configuredLimit = values.get(MAX_EXACT_MODELS_OPTION);
        if (configuredLimit != null && !configuredLimit.isEmpty()) {
            try {
                int candidateLimit = Integer.parseInt(configuredLimit);
                if (candidateLimit <= 0) {
                    limitError = "Unsupported -A" + MAX_EXACT_MODELS_OPTION
                            + " value; expected a positive integer.";
                }
                else {
                    parsedLimit = candidateLimit;
                }
            }
            catch (NumberFormatException ignored) {
                limitError = "Unsupported -A" + MAX_EXACT_MODELS_OPTION
                        + " value; expected a positive integer.";
            }
        }
        return new ProcessorOptions(
                parsedMode,
                parsedLimit,
                modeError == null ? limitError : modeError);
    }

    public ProcessorMode mode() {
        return mode;
    }

    public int maxExactModels() {
        return maxExactModels;
    }

    public String validationError() {
        return validationError;
    }
}
