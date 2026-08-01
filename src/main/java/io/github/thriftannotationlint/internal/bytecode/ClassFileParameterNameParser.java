package io.github.thriftannotationlint.internal.bytecode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, bounded class-file parser for the LocalVariableTable behavior exposed by Paranamer 2.8.
 * It deliberately ignores MethodParameters because supported Swift releases do the same.
 */
final class ClassFileParameterNameParser {
    private static final int MAX_METHODS_PER_CLASS = 8192;
    private static final int MAX_LVT_ENTRIES_PER_CLASS = 65536;
    private static final long MAX_CLASS_LOOKUP_WEIGHT_BYTES = 2L * 1024L * 1024L;
    private static final long PARSED_CLASS_BASE_WEIGHT = 128L;
    private static final long METHOD_ENTRY_BASE_WEIGHT = 64L;
    private static final long METHOD_LOOKUP_BASE_WEIGHT = 48L;
    private static final long LIST_BASE_WEIGHT = 32L;
    private static final long REFERENCE_WEIGHT = 8L;
    private static final long STRING_BASE_WEIGHT = 40L;
    private static final long UTF16_BYTES_PER_CHARACTER = 2L;
    private final ClassFileDataReader dataReader = new ClassFileDataReader();
    private final ClassFileConstantPoolReader constantPoolReader =
            new ClassFileConstantPoolReader(dataReader);
    private final MethodDescriptorParser descriptorParser = new MethodDescriptorParser();

    ParsedClass parse(InputStream input) throws IOException {
        byte[] classBytes = dataReader.readClassBytes(input);
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(classBytes));
        if (data.readInt() != ClassFileFormat.MAGIC) {
            throw new IOException("Invalid class-file magic");
        }
        data.readUnsignedShort();
        data.readUnsignedShort();
        String[] utf8 = constantPoolReader.read(data);

        data.readUnsignedShort();
        data.readUnsignedShort();
        data.readUnsignedShort();
        dataReader.skipU2Table(data);
        skipMembers(data);

        Map<String, MethodLookup> methods = new LinkedHashMap<String, MethodLookup>();
        int methodCount = data.readUnsignedShort();
        if (methodCount > MAX_METHODS_PER_CLASS) {
            throw new IOException("Class declares too many methods");
        }
        ParseBudget budget = new ParseBudget();
        for (int methodIndex = 0; methodIndex < methodCount; methodIndex++) {
            int access = data.readUnsignedShort();
            String name = dataReader.utf8Value(utf8, data.readUnsignedShort());
            String descriptor = dataReader.utf8Value(utf8, data.readUnsignedShort());
            MethodDescriptorParser.Layout layout = descriptorParser.layout(
                    descriptor, (access & ClassFileFormat.ACCESS_STATIC) != 0);
            MethodParameterNames parameterNames = new MethodParameterNames();

            int attributeCount = data.readUnsignedShort();
            for (int attributeIndex = 0; attributeIndex < attributeCount; attributeIndex++) {
                String attributeName = dataReader.utf8Value(utf8, data.readUnsignedShort());
                int attributeLength = data.readInt();
                if ("Code".equals(attributeName)) {
                    byte[] attribute = dataReader.readBytes(data, attributeLength);
                    readCode(attribute, utf8, layout, parameterNames, budget);
                }
                else {
                    dataReader.skipAttribute(data, attributeLength);
                }
            }
            String key = name + "\u0000" + descriptorParser.parameters(descriptor);
            if (!methods.containsKey(key)) {
                MethodLookup result = parameterNames.toLookupResult(layout.parameterCount);
                budget.addLookupWeight(key, result);
                methods.put(key, result);
            }
        }
        return new ParsedClass(methods);
    }

    static MethodLookup classifyLocalVariableNames(
            List<String> names,
            int parameterCount) {
        if (parameterCount == 0) {
            return MethodLookup.found(Collections.<String>emptyList());
        }
        boolean debugInfoPresent = false;
        for (int index = 0; index < names.size(); index++) {
            if (!("arg" + index).equals(names.get(index))) {
                debugInfoPresent = true;
            }
        }
        if (!debugInfoPresent) {
            return MethodLookup.absent();
        }
        if (names.size() < parameterCount) {
            return MethodLookup.invalid(
                    "LocalVariableTable exposes only " + names.size() + " of "
                            + parameterCount + " parameter names; supported Swift releases "
                            + "would fail while indexing that partial result");
        }
        return MethodLookup.found(names.subList(0, parameterCount));
    }

    private void skipMembers(DataInputStream data) throws IOException {
        int count = data.readUnsignedShort();
        for (int index = 0; index < count; index++) {
            data.readUnsignedShort();
            data.readUnsignedShort();
            data.readUnsignedShort();
            skipAttributes(data);
        }
    }

    private void skipAttributes(DataInputStream data) throws IOException {
        int count = data.readUnsignedShort();
        for (int index = 0; index < count; index++) {
            data.readUnsignedShort();
            int length = data.readInt();
            dataReader.skipAttribute(data, length);
        }
    }

    private void readCode(
            byte[] attribute,
            String[] utf8,
            MethodDescriptorParser.Layout layout,
            MethodParameterNames names,
            ParseBudget budget) throws IOException {
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(attribute));
        data.readUnsignedShort();
        data.readUnsignedShort();
        int codeLength = data.readInt();
        if (codeLength < 0 || codeLength > ClassFileFormat.MAX_CODE_LENGTH) {
            throw new IOException("Invalid Code attribute length");
        }
        dataReader.skipFully(data, codeLength);
        int exceptionCount = data.readUnsignedShort();
        dataReader.skipFully(data, exceptionCount * ClassFileFormat.EXCEPTION_TABLE_ENTRY_BYTES);
        int attributeCount = data.readUnsignedShort();
        byte[] localVariableTable = null;
        for (int index = 0; index < attributeCount; index++) {
            String name = dataReader.utf8Value(utf8, data.readUnsignedShort());
            int attributeLength = data.readInt();
            if ("LocalVariableTable".equals(name)) {
                // Paranamer 2.8 retains the last LocalVariableTable attribute encountered in a
                // Code attribute. Multiple tables are legal, so do not concatenate their rows.
                localVariableTable = dataReader.readBytes(data, attributeLength);
            }
            else {
                dataReader.skipAttribute(data, attributeLength);
            }
        }
        if (localVariableTable != null) {
            readLocalVariableTable(localVariableTable, utf8, layout, names, budget);
        }
    }

    private void readLocalVariableTable(
            byte[] attribute,
            String[] utf8,
            MethodDescriptorParser.Layout layout,
            MethodParameterNames names,
            ParseBudget budget) throws IOException {
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(attribute));
        int count = data.readUnsignedShort();
        budget.addLocalVariableEntries(count);
        for (int index = 0; index < count; index++) {
            data.readUnsignedShort();
            data.readUnsignedShort();
            String name = dataReader.utf8Value(utf8, data.readUnsignedShort());
            data.readUnsignedShort();
            int slot = data.readUnsignedShort();
            // Paranamer collects every entry in the contiguous parameter-slot range in table
            // order. It does not require start_pc == 0, even for woven bytecode.
            if (slot >= layout.firstSlot && slot < layout.slotLimit) {
                names.add(name);
            }
        }
    }

    static final class ParsedClass {
        private final Map<String, MethodLookup> methods;

        private ParsedClass(Map<String, MethodLookup> methods) {
            this.methods = Collections.unmodifiableMap(
                    new LinkedHashMap<String, MethodLookup>(methods));
        }

        MethodLookup find(String key) {
            MethodLookup result = methods.get(key);
            return result == null ? MethodLookup.absent() : result;
        }

        long estimatedWeight(String binaryName) {
            long weight = PARSED_CLASS_BASE_WEIGHT + stringWeight(binaryName);
            for (Map.Entry<String, MethodLookup> method : methods.entrySet()) {
                weight += METHOD_ENTRY_BASE_WEIGHT + stringWeight(method.getKey())
                        + method.getValue().estimatedWeight();
            }
            return weight;
        }
    }

    static final class MethodLookup {
        private final List<String> names;
        private final String failure;

        private MethodLookup(List<String> names, String failure) {
            this.names = names == null
                    ? null
                    : Collections.unmodifiableList(new ArrayList<String>(names));
            this.failure = failure;
        }

        static MethodLookup found(List<String> names) {
            return new MethodLookup(names, null);
        }

        static MethodLookup absent() {
            return new MethodLookup(null, null);
        }

        static MethodLookup invalid(String failure) {
            return new MethodLookup(null, failure);
        }

        boolean isFound() {
            return names != null;
        }

        boolean isInvalid() {
            return failure != null;
        }

        List<String> names() {
            return names == null ? null : new ArrayList<String>(names);
        }

        String failure() {
            return failure;
        }

        long estimatedWeight() {
            long weight = METHOD_LOOKUP_BASE_WEIGHT;
            if (failure != null) {
                weight += stringWeight(failure);
            }
            if (names != null) {
                weight += LIST_BASE_WEIGHT + REFERENCE_WEIGHT * names.size();
                for (String name : names) {
                    weight += stringWeight(name);
                }
            }
            return weight;
        }
    }

    private static final class MethodParameterNames {
        private final List<String> names = new ArrayList<String>();

        void add(String name) {
            names.add(name);
        }

        MethodLookup toLookupResult(int parameterCount) {
            return classifyLocalVariableNames(names, parameterCount);
        }
    }

    private static final class ParseBudget {
        private int localVariableEntries;
        private long lookupWeightBytes;

        void addLocalVariableEntries(int count) throws IOException {
            localVariableEntries += count;
            if (localVariableEntries > MAX_LVT_ENTRIES_PER_CLASS) {
                throw new IOException("Class declares too many local-variable entries");
            }
        }

        void addLookupWeight(String key, MethodLookup result) throws IOException {
            lookupWeightBytes += METHOD_ENTRY_BASE_WEIGHT
                    + stringWeight(key) + result.estimatedWeight();
            if (lookupWeightBytes > MAX_CLASS_LOOKUP_WEIGHT_BYTES) {
                throw new IOException(
                        "Class parameter metadata exceeds the retained lookup safety limit");
            }
        }
    }

    private static long stringWeight(String value) {
        return value == null
                ? 0
                : STRING_BASE_WEIGHT + UTF16_BYTES_PER_CHARACTER * value.length();
    }
}
