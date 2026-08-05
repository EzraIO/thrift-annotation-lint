package io.github.thriftannotationlint.internal.bytecode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, bounded class-file parser for the parameter metadata consumed by supported codecs.
 * Swift and Drift views remain separate because their lookup order and LVT semantics differ.
 */
final class ClassFileParameterNameParser {
    private static final String CODE_ATTRIBUTE = "Code";
    private static final String LOCAL_VARIABLE_TABLE_ATTRIBUTE = "LocalVariableTable";
    private static final String METHOD_PARAMETERS_ATTRIBUTE = "MethodParameters";
    private static final String METHOD_KEY_SEPARATOR = "\u0000";
    private static final int MAX_METHODS_PER_CLASS = 8192;
    private static final int MAX_LVT_ENTRIES_PER_CLASS = 65536;
    private static final int MAX_METHOD_PARAMETER_ENTRIES_PER_CLASS = 65536;
    private static final long MAX_CLASS_LOOKUP_WEIGHT_BYTES = 2L * 1024L * 1024L;
    private static final long PARSED_CLASS_BASE_WEIGHT = 128L;
    private static final long METHOD_ENTRY_BASE_WEIGHT = 64L;
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
        dataReader.skipU2Values(data, ClassFileFormat.CLASS_VERSION_U2_FIELDS);
        String[] utf8 = constantPoolReader.read(data);

        dataReader.skipU2Values(data, ClassFileFormat.CLASS_IDENTITY_U2_FIELDS);
        dataReader.skipU2Table(data);
        skipMembers(data);

        Map<String, MethodParameterMetadata.Lookups> methods =
                new LinkedHashMap<String, MethodParameterMetadata.Lookups>();
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
            MethodParameterMetadata parameterNames = new MethodParameterMetadata();

            int attributeCount = data.readUnsignedShort();
            for (int attributeIndex = 0; attributeIndex < attributeCount; attributeIndex++) {
                String attributeName = dataReader.utf8Value(utf8, data.readUnsignedShort());
                int attributeLength = data.readInt();
                if (CODE_ATTRIBUTE.equals(attributeName)) {
                    byte[] attribute = dataReader.readBytes(data, attributeLength);
                    readCode(attribute, utf8, layout, parameterNames, budget);
                }
                else if (METHOD_PARAMETERS_ATTRIBUTE.equals(attributeName)) {
                    byte[] attribute = dataReader.readBytes(data, attributeLength);
                    budget.addMethodParameterEntries(
                            parameterNames.readMethodParameters(attribute, utf8, dataReader));
                }
                else {
                    dataReader.skipAttribute(data, attributeLength);
                }
            }
            String key = name + METHOD_KEY_SEPARATOR + descriptorParser.parameters(descriptor);
            if (!methods.containsKey(key)) {
                MethodParameterMetadata.Lookups result = parameterNames.resolve(layout);
                budget.addLookupWeight(key, result);
                methods.put(key, result);
            }
        }
        return new ParsedClass(methods);
    }

    static ParameterNameLookup classifyLocalVariableNames(
            List<String> names,
            int parameterCount) {
        if (parameterCount == 0) {
            return ParameterNameLookup.found(Collections.<String>emptyList());
        }
        boolean debugInfoPresent = false;
        for (int index = 0; index < names.size(); index++) {
            if (!("arg" + index).equals(names.get(index))) {
                debugInfoPresent = true;
            }
        }
        if (!debugInfoPresent) {
            return ParameterNameLookup.absent();
        }
        if (names.size() < parameterCount) {
            return ParameterNameLookup.invalid(
                    "LocalVariableTable exposes only " + names.size() + " of "
                            + parameterCount + " parameter names; supported Swift releases "
                            + "would fail while indexing that partial result");
        }
        return ParameterNameLookup.found(names.subList(0, parameterCount));
    }

    private void skipMembers(DataInputStream data) throws IOException {
        int count = data.readUnsignedShort();
        for (int index = 0; index < count; index++) {
            dataReader.skipU2Values(data, ClassFileFormat.MEMBER_HEADER_U2_FIELDS);
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
            MethodParameterMetadata names,
            ParseBudget budget) throws IOException {
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(attribute));
        dataReader.skipU2Values(data, ClassFileFormat.CODE_HEADER_U2_FIELDS);
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
            if (LOCAL_VARIABLE_TABLE_ATTRIBUTE.equals(name)) {
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
            MethodParameterMetadata names,
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
                names.addLocalVariable(name, slot);
            }
        }
    }

    static final class ParsedClass {
        private final Map<String, MethodParameterMetadata.Lookups> methods;

        private ParsedClass(Map<String, MethodParameterMetadata.Lookups> methods) {
            this.methods = Collections.unmodifiableMap(
                    new LinkedHashMap<String, MethodParameterMetadata.Lookups>(methods));
        }

        ParameterNameLookup find(String key) {
            return findSwift(key);
        }

        ParameterNameLookup findSwift(String key) {
            MethodParameterMetadata.Lookups result = methods.get(key);
            return result == null ? ParameterNameLookup.absent() : result.swift();
        }

        ParameterNameLookup findDrift(String key) {
            MethodParameterMetadata.Lookups result = methods.get(key);
            return result == null ? ParameterNameLookup.absent() : result.drift();
        }

        long estimatedWeight(String binaryName) {
            long weight = PARSED_CLASS_BASE_WEIGHT + stringWeight(binaryName);
            for (Map.Entry<String, MethodParameterMetadata.Lookups> method
                    : methods.entrySet()) {
                weight += METHOD_ENTRY_BASE_WEIGHT + stringWeight(method.getKey())
                        + method.getValue().estimatedWeight();
            }
            return weight;
        }
    }

    private static final class ParseBudget {
        private int localVariableEntries;
        private int methodParameterEntries;
        private long lookupWeightBytes;

        void addLocalVariableEntries(int count) throws IOException {
            localVariableEntries += count;
            if (localVariableEntries > MAX_LVT_ENTRIES_PER_CLASS) {
                throw new IOException("Class declares too many local-variable entries");
            }
        }

        void addMethodParameterEntries(int count) throws IOException {
            methodParameterEntries += count;
            if (methodParameterEntries > MAX_METHOD_PARAMETER_ENTRIES_PER_CLASS) {
                throw new IOException("Class declares too many method-parameter entries");
            }
        }

        void addLookupWeight(
                String key,
                MethodParameterMetadata.Lookups result) throws IOException {
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
