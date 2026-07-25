package io.github.thriftannotationlint;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessorOptionsTest {
    @Test
    void usesStableDefaults() {
        ProcessorOptions options = ProcessorOptions.parse(Collections.<String, String>emptyMap());

        assertEquals(ProcessorMode.STRICT, options.mode());
        assertEquals(512, options.maxExactModels());
        assertNull(options.validationError());
    }

    @Test
    void parsesSupportedValues() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put(ThriftAnnotationLintProcessor.MODE_OPTION, "warning");
        values.put(ThriftAnnotationLintProcessor.MAX_EXACT_MODELS_OPTION, "17");

        ProcessorOptions options = ProcessorOptions.parse(values);

        assertEquals(ProcessorMode.WARNING, options.mode());
        assertEquals(17, options.maxExactModels());
        assertNull(options.validationError());
    }

    @Test
    void keepsModeValidationPrecedenceWhenBothOptionsAreInvalid() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put(ThriftAnnotationLintProcessor.MODE_OPTION, "WARN");
        values.put(ThriftAnnotationLintProcessor.MAX_EXACT_MODELS_OPTION, "0");

        ProcessorOptions options = ProcessorOptions.parse(values);

        assertEquals(ProcessorMode.STRICT, options.mode());
        assertTrue(options.validationError().contains(ThriftAnnotationLintProcessor.MODE_OPTION));
    }

    @Test
    void rejectsNonPositiveAndNonNumericLimits() {
        Map<String, String> zero = Collections.singletonMap(
                ThriftAnnotationLintProcessor.MAX_EXACT_MODELS_OPTION, "0");
        Map<String, String> text = Collections.singletonMap(
                ThriftAnnotationLintProcessor.MAX_EXACT_MODELS_OPTION, "many");

        assertTrue(ProcessorOptions.parse(zero).validationError()
                .contains(ThriftAnnotationLintProcessor.MAX_EXACT_MODELS_OPTION));
        assertEquals(512, ProcessorOptions.parse(zero).maxExactModels());
        assertTrue(ProcessorOptions.parse(text).validationError()
                .contains(ThriftAnnotationLintProcessor.MAX_EXACT_MODELS_OPTION));
        assertEquals(512, ProcessorOptions.parse(text).maxExactModels());
    }
}
