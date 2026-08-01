package io.github.thriftannotationlint.internal.bytecode;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MethodDescriptorParserTest {
    private final MethodDescriptorParser parser = new MethodDescriptorParser();

    @Test
    void preservesInstanceAndWideParameterSlots() throws IOException {
        MethodDescriptorParser.Layout layout = parser.layout("(IJD[Ljava/lang/String;)V", false);

        assertEquals(4, layout.parameterCount);
        assertEquals(1, layout.firstSlot);
        assertEquals(7, layout.slotLimit);
    }

    @Test
    void preservesStaticParameterSlots() throws IOException {
        MethodDescriptorParser.Layout layout = parser.layout("([JLjava/lang/String;)V", true);

        assertEquals(2, layout.parameterCount);
        assertEquals(0, layout.firstSlot);
        assertEquals(2, layout.slotLimit);
    }

    @Test
    void extractsOnlyTheParameterDescriptor() throws IOException {
        assertEquals("(Ljava/lang/String;I)",
                parser.parameters("(Ljava/lang/String;I)Ljava/lang/Object;"));
    }

    @Test
    void rejectsMalformedDescriptorsWithTheCompatibleMessage() {
        IOException failure = assertThrows(
                IOException.class,
                () -> parser.layout("([", false));

        assertEquals("Invalid method descriptor ([", failure.getMessage());
    }
}
