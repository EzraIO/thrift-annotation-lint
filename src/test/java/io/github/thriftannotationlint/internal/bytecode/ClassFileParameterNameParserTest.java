package io.github.thriftannotationlint.internal.bytecode;


import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassFileParameterNameParserTest {
    @Test
    void parsesInstanceAndStaticParametersAcrossWideAndArraySlots() throws Exception {
        InputStream input = ClassFileParameterNameParserTest.class.getResourceAsStream(
                "ClassFileParameterNameParserTest$ParserFixture.class");
        assertNotNull(input);
        try {
            ClassFileParameterNameParser.ParsedClass parsed =
                    new ClassFileParameterNameParser().parse(input);

            ParameterNameLookup instance = parsed.find(
                    "instance\u0000(J[Ljava/lang/String;DI)");
            assertTrue(instance.isFound());
            assertEquals(
                    Arrays.asList("wide", "values", "fraction", "count"),
                    instance.names());

            ParameterNameLookup staticMethod = parsed.find(
                    "staticCall\u0000(JD[Ljava/lang/Object;)");
            assertTrue(staticMethod.isFound());
            assertEquals(
                    Arrays.asList("first", "second", "third"),
                    staticMethod.names());

            ParameterNameLookup constructor =
                    parsed.find("<init>\u0000()");
            assertTrue(constructor.isFound());
            assertEquals(Collections.emptyList(), constructor.names());
            assertFalse(parsed.find("missing\u0000()").isFound());
        }
        finally {
            input.close();
        }
    }

    @Test
    void reproducesParanamerPartialOverCompleteAndArgFallbackSemantics() {
        ParameterNameLookup partial =
                ClassFileParameterNameParser.classifyLocalVariableNames(
                        Collections.singletonList("left"), 2);
        assertTrue(partial.isInvalid());
        assertTrue(partial.failure().contains("only 1 of 2"));

        ParameterNameLookup overComplete =
                ClassFileParameterNameParser.classifyLocalVariableNames(
                        Arrays.asList("left", "right", "wovenAlias"), 2);
        assertTrue(overComplete.isFound());
        assertEquals(Arrays.asList("left", "right"), overComplete.names());

        ParameterNameLookup generatedNames =
                ClassFileParameterNameParser.classifyLocalVariableNames(
                        Arrays.asList("arg0", "arg1"), 2);
        assertFalse(generatedNames.isFound());
        assertFalse(generatedNames.isInvalid());

        ParameterNameLookup noParameters =
                ClassFileParameterNameParser.classifyLocalVariableNames(
                        Collections.singletonList("wovenAlias"), 0);
        assertTrue(noParameters.isFound());
        assertEquals(Collections.emptyList(), noParameters.names());
    }

    @Test
    void usesOnlyTheLastLocalVariableTableLikeParanamer() throws Exception {
        ClassFileParameterNameParser.ParsedClass parsed =
                new ClassFileParameterNameParser().parse(
                        new ByteArrayInputStream(classWithTwoLocalVariableTables()));

        ParameterNameLookup lookup = parsed.find("call\u0000(II)");
        assertTrue(lookup.isFound());
        assertEquals(Arrays.asList("left", "right"), lookup.names());
    }

    @Test
    void rejectsTruncatedAndOversizedClassFiles() {
        final ClassFileParameterNameParser parser = new ClassFileParameterNameParser();
        assertThrows(IOException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() throws Throwable {
                parser.parse(new ByteArrayInputStream(new byte[]{
                        (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE
                }));
            }
        });

        final byte[] oversized = new byte[16 * 1024 * 1024 + 1];
        assertThrows(IOException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() throws Throwable {
                parser.parse(new ByteArrayInputStream(oversized));
            }
        });
    }

    @Test
    void rejectsEachIndependentMetadataBudget() throws Exception {
        ClassFileParameterNameParser parser = new ClassFileParameterNameParser();

        IOException methods = assertThrows(
                IOException.class,
                () -> parser.parse(new ByteArrayInputStream(
                        classWithMethodCount(8193))));
        assertEquals("Class declares too many methods", methods.getMessage());

        IOException localVariables = assertThrows(
                IOException.class,
                () -> parser.parse(new ByteArrayInputStream(
                        classWithLocalVariableEntryCounts(32768, 32769))));
        assertEquals(
                "Class declares too many local-variable entries",
                localVariables.getMessage());

        IOException retainedLookups = assertThrows(
                IOException.class,
                () -> parser.parse(new ByteArrayInputStream(
                        classWithWeightedMethodNames(4000, 240))));
        assertEquals(
                "Class parameter metadata exceeds the retained lookup safety limit",
                retainedLookups.getMessage());
    }

    private static byte[] classWithTwoLocalVariableTables() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeInt(0xCAFEBABE);
        data.writeShort(0);
        data.writeShort(52);

        data.writeShort(14);
        writeUtf8(data, "Fixture");       // 1
        data.writeByte(7);
        data.writeShort(1);                // 2: Class Fixture
        writeUtf8(data, "java/lang/Object"); // 3
        data.writeByte(7);
        data.writeShort(3);                // 4: Class Object
        writeUtf8(data, "call");          // 5
        writeUtf8(data, "(II)V");         // 6
        writeUtf8(data, "Code");          // 7
        writeUtf8(data, "LocalVariableTable"); // 8
        writeUtf8(data, "firstAlias");    // 9
        writeUtf8(data, "secondAlias");   // 10
        writeUtf8(data, "left");          // 11
        writeUtf8(data, "right");         // 12
        writeUtf8(data, "I");             // 13

        data.writeShort(0x0021);
        data.writeShort(2);
        data.writeShort(4);
        data.writeShort(0);
        data.writeShort(0);
        data.writeShort(1);

        data.writeShort(0x0009);
        data.writeShort(5);
        data.writeShort(6);
        data.writeShort(1);
        data.writeShort(7);
        data.writeInt(69);
        data.writeShort(0);
        data.writeShort(2);
        data.writeInt(1);
        data.writeByte(0xB1);
        data.writeShort(0);
        data.writeShort(2);
        writeLocalVariableTable(data, 9, 10);
        writeLocalVariableTable(data, 11, 12);
        data.writeShort(0);
        data.flush();
        return bytes.toByteArray();
    }

    private static byte[] classWithMethodCount(int methodCount) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        writeEmptyClassHeader(data, 1);
        data.writeShort(methodCount);
        data.flush();
        return bytes.toByteArray();
    }

    private static byte[] classWithLocalVariableEntryCounts(
            int firstCount,
            int secondCount) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeInt(0xCAFEBABE);
        data.writeShort(0);
        data.writeShort(52);
        data.writeShort(7);
        writeUtf8(data, "call");
        writeUtf8(data, "(I)V");
        writeUtf8(data, "Code");
        writeUtf8(data, "LocalVariableTable");
        writeUtf8(data, "value");
        writeUtf8(data, "I");
        writeEmptyClassBodyPrefix(data);
        data.writeShort(1);
        data.writeShort(0x0009);
        data.writeShort(1);
        data.writeShort(2);
        data.writeShort(2);
        writeCodeWithLocalVariables(data, firstCount);
        writeCodeWithLocalVariables(data, secondCount);
        data.flush();
        return bytes.toByteArray();
    }

    private static byte[] classWithWeightedMethodNames(
            int methodCount,
            int paddingLength) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeInt(0xCAFEBABE);
        data.writeShort(0);
        data.writeShort(52);
        data.writeShort(methodCount + 2);
        String padding = repeated('x', paddingLength);
        for (int index = 0; index < methodCount; index++) {
            writeUtf8(data, "method" + index + padding);
        }
        writeUtf8(data, "()V");
        writeEmptyClassBodyPrefix(data);
        data.writeShort(methodCount);
        for (int index = 0; index < methodCount; index++) {
            data.writeShort(0x0009);
            data.writeShort(index + 1);
            data.writeShort(methodCount + 1);
            data.writeShort(0);
        }
        data.flush();
        return bytes.toByteArray();
    }

    private static void writeEmptyClassHeader(
            DataOutputStream data,
            int constantPoolCount) throws IOException {
        data.writeInt(0xCAFEBABE);
        data.writeShort(0);
        data.writeShort(52);
        data.writeShort(constantPoolCount);
        writeEmptyClassBodyPrefix(data);
    }

    private static void writeEmptyClassBodyPrefix(DataOutputStream data) throws IOException {
        data.writeShort(0x0021);
        data.writeShort(0);
        data.writeShort(0);
        data.writeShort(0);
        data.writeShort(0);
    }

    private static void writeCodeWithLocalVariables(
            DataOutputStream data,
            int count) throws IOException {
        int localVariableTableLength = 2 + count * 10;
        int codeLength = 18 + localVariableTableLength;
        data.writeShort(3);
        data.writeInt(codeLength);
        data.writeShort(0);
        data.writeShort(1);
        data.writeInt(0);
        data.writeShort(0);
        data.writeShort(1);
        data.writeShort(4);
        data.writeInt(localVariableTableLength);
        data.writeShort(count);
        for (int index = 0; index < count; index++) {
            data.writeShort(0);
            data.writeShort(0);
            data.writeShort(5);
            data.writeShort(6);
            data.writeShort(0);
        }
    }

    private static String repeated(char value, int count) {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }

    private static void writeLocalVariableTable(
            DataOutputStream data,
            int firstName,
            int secondName) throws IOException {
        data.writeShort(8);
        data.writeInt(22);
        data.writeShort(2);
        writeLocalVariable(data, firstName, 0);
        writeLocalVariable(data, secondName, 1);
    }

    private static void writeLocalVariable(
            DataOutputStream data,
            int name,
            int slot) throws IOException {
        data.writeShort(0);
        data.writeShort(1);
        data.writeShort(name);
        data.writeShort(13);
        data.writeShort(slot);
    }

    private static void writeUtf8(DataOutputStream data, String value) throws IOException {
        data.writeByte(1);
        data.writeUTF(value);
    }

    static final class ParserFixture {
        void instance(long wide, String[] values, double fraction, int count) {
            if (wide == count && fraction == values.length) {
                throw new AssertionError();
            }
        }

        static void staticCall(long first, double second, Object[] third) {
            if (first == second && third.length == 0) {
                throw new AssertionError();
            }
        }
    }
}
