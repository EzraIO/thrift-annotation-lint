package io.github.thriftannotationlint;

import org.junit.jupiter.api.Test;

import javax.annotation.processing.Processor;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessorRegistrationTest {
    private static final String SERVICE_RESOURCE =
            "/META-INF/services/javax.annotation.processing.Processor";
    private static final String GRADLE_RESOURCE =
            "/META-INF/gradle/incremental.annotation.processors";

    @Test
    void serviceFileRegistersOnlyThePublicProcessor() throws IOException {
        List<String> registrations = readEntries(SERVICE_RESOURCE);

        assertEquals(1, registrations.size(), "The service file must have one processor entry");
        assertEquals(ThriftAnnotationLintProcessor.class.getName(), registrations.get(0));
    }

    @Test
    void gradleMetadataDeclaresTheProcessorAggregating() throws IOException {
        List<String> registrations = readEntries(GRADLE_RESOURCE);

        assertEquals(1, registrations.size(), "The Gradle metadata must have one processor entry");
        assertEquals(ThriftAnnotationLintProcessor.class.getName() + ",aggregating", registrations.get(0));
    }

    @Test
    void processorIsDiscoverableThroughTheStandardServiceLoader() {
        Iterator<Processor> processors = ServiceLoader.load(Processor.class).iterator();
        boolean found = false;
        while (processors.hasNext()) {
            if (processors.next().getClass().equals(ThriftAnnotationLintProcessor.class)) {
                found = true;
                break;
            }
        }
        assertTrue(found, "ThriftAnnotationLintProcessor must be discoverable through ServiceLoader");
    }

    private static List<String> readEntries(String resource) throws IOException {
        InputStream stream = ProcessorRegistrationTest.class.getResourceAsStream(resource);
        assertNotNull(stream, "Packaged metadata is missing: " + resource);

        List<String> registrations = new ArrayList<String>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    registrations.add(trimmed);
                }
            }
        }
        return registrations;
    }
}
