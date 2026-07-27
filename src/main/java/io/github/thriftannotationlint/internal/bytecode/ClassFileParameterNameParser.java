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
    private static final int CLASS_FILE_MAGIC = 0xCAFEBABE;
    private static final int MAX_CLASS_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ATTRIBUTE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_METHODS_PER_CLASS = 8192;
    private static final int MAX_LVT_ENTRIES_PER_CLASS = 65536;
    private static final long MAX_CLASS_LOOKUP_WEIGHT_BYTES = 2L * 1024L * 1024L;

    ParsedClass parse(InputStream input) throws IOException {
        byte[] classBytes = readClassBytes(input);
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(classBytes));
        if (data.readInt() != CLASS_FILE_MAGIC) {
            throw new IOException("Invalid class-file magic");
        }
        data.readUnsignedShort();
        data.readUnsignedShort();
        String[] utf8 = readConstantPool(data);

        data.readUnsignedShort();
        data.readUnsignedShort();
        data.readUnsignedShort();
        skipU2Table(data);
        skipMembers(data);

        Map<String, MethodLookup> methods = new LinkedHashMap<String, MethodLookup>();
        int methodCount = data.readUnsignedShort();
        if (methodCount > MAX_METHODS_PER_CLASS) {
            throw new IOException("Class declares too many methods");
        }
        ParseBudget budget = new ParseBudget();
        for (int methodIndex = 0; methodIndex < methodCount; methodIndex++) {
            int access = data.readUnsignedShort();
            String name = utf8Value(utf8, data.readUnsignedShort());
            String descriptor = utf8Value(utf8, data.readUnsignedShort());
            ParameterLayout layout = parameterLayout(descriptor, (access & 0x0008) != 0);
            MethodParameterNames parameterNames = new MethodParameterNames();

            int attributeCount = data.readUnsignedShort();
            for (int attributeIndex = 0; attributeIndex < attributeCount; attributeIndex++) {
                String attributeName = utf8Value(utf8, data.readUnsignedShort());
                int attributeLength = data.readInt();
                if ("Code".equals(attributeName)) {
                    byte[] attribute = readBytes(data, attributeLength);
                    readCode(attribute, utf8, layout, parameterNames, budget);
                }
                else {
                    skipAttribute(data, attributeLength);
                }
            }
            String key = name + "\u0000" + parameterDescriptor(descriptor);
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

    private String[] readConstantPool(DataInputStream data) throws IOException {
        int count = data.readUnsignedShort();
        String[] utf8 = new String[count];
        for (int index = 1; index < count; index++) {
            int tag = data.readUnsignedByte();
            if (tag == 1) {
                utf8[index] = data.readUTF();
            }
            else if (tag == 3 || tag == 4) {
                skipFully(data, 4);
            }
            else if (tag == 5 || tag == 6) {
                skipFully(data, 8);
                index++;
            }
            else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                skipFully(data, 2);
            }
            else if (tag == 9 || tag == 10 || tag == 11 || tag == 12
                    || tag == 17 || tag == 18) {
                skipFully(data, 4);
            }
            else if (tag == 15) {
                skipFully(data, 3);
            }
            else {
                throw new IOException("Unsupported class-file constant-pool tag " + tag);
            }
        }
        return utf8;
    }

    private void skipU2Table(DataInputStream data) throws IOException {
        int count = data.readUnsignedShort();
        skipFully(data, count * 2);
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
            skipAttribute(data, length);
        }
    }

    private void readCode(
            byte[] attribute,
            String[] utf8,
            ParameterLayout layout,
            MethodParameterNames names,
            ParseBudget budget) throws IOException {
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(attribute));
        data.readUnsignedShort();
        data.readUnsignedShort();
        int codeLength = data.readInt();
        if (codeLength < 0 || codeLength > 65535) {
            throw new IOException("Invalid Code attribute length");
        }
        skipFully(data, codeLength);
        int exceptionCount = data.readUnsignedShort();
        skipFully(data, exceptionCount * 8);
        int attributeCount = data.readUnsignedShort();
        byte[] localVariableTable = null;
        for (int index = 0; index < attributeCount; index++) {
            String name = utf8Value(utf8, data.readUnsignedShort());
            int attributeLength = data.readInt();
            if ("LocalVariableTable".equals(name)) {
                // Paranamer 2.8 retains the last LocalVariableTable attribute encountered in a
                // Code attribute. Multiple tables are legal, so do not concatenate their rows.
                localVariableTable = readBytes(data, attributeLength);
            }
            else {
                skipAttribute(data, attributeLength);
            }
        }
        if (localVariableTable != null) {
            readLocalVariableTable(localVariableTable, utf8, layout, names, budget);
        }
    }

    private void readLocalVariableTable(
            byte[] attribute,
            String[] utf8,
            ParameterLayout layout,
            MethodParameterNames names,
            ParseBudget budget) throws IOException {
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(attribute));
        int count = data.readUnsignedShort();
        budget.addLocalVariableEntries(count);
        for (int index = 0; index < count; index++) {
            data.readUnsignedShort();
            data.readUnsignedShort();
            String name = utf8Value(utf8, data.readUnsignedShort());
            data.readUnsignedShort();
            int slot = data.readUnsignedShort();
            // Paranamer collects every entry in the contiguous parameter-slot range in table
            // order. It does not require start_pc == 0, even for woven bytecode.
            if (slot >= layout.firstSlot && slot < layout.slotLimit) {
                names.add(name);
            }
        }
    }

    private ParameterLayout parameterLayout(String descriptor, boolean isStatic)
            throws IOException {
        int parameterCount = 0;
        int firstSlot = isStatic ? 0 : 1;
        int slot = firstSlot;
        int index = 1;
        while (index < descriptor.length() && descriptor.charAt(index) != ')') {
            parameterCount++;
            char type = descriptor.charAt(index);
            boolean array = false;
            while (type == '[') {
                array = true;
                index++;
                if (index >= descriptor.length()) {
                    throw new IOException("Invalid method descriptor " + descriptor);
                }
                type = descriptor.charAt(index);
            }
            if (type == 'L') {
                index = descriptor.indexOf(';', index);
                if (index < 0) {
                    throw new IOException("Invalid method descriptor " + descriptor);
                }
            }
            if (array || type == 'L') {
                slot++;
            }
            else {
                slot += type == 'J' || type == 'D' ? 2 : 1;
            }
            index++;
        }
        if (index >= descriptor.length() || descriptor.charAt(index) != ')') {
            throw new IOException("Invalid method descriptor " + descriptor);
        }
        return new ParameterLayout(parameterCount, firstSlot, slot);
    }

    private String parameterDescriptor(String descriptor) throws IOException {
        int end = descriptor.indexOf(')');
        if (descriptor.isEmpty() || descriptor.charAt(0) != '(' || end < 0) {
            throw new IOException("Invalid method descriptor " + descriptor);
        }
        return descriptor.substring(0, end + 1);
    }

    private byte[] readClassBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) {
                continue;
            }
            total += count;
            if (total > MAX_CLASS_BYTES) {
                throw new IOException("Class file exceeds parser safety limit");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private byte[] readBytes(DataInputStream data, int length) throws IOException {
        if (length < 0 || length > MAX_ATTRIBUTE_BYTES) {
            throw new IOException("Invalid or oversized class-file attribute length " + length);
        }
        byte[] result = new byte[length];
        data.readFully(result);
        return result;
    }

    private void skipAttribute(DataInputStream data, int length) throws IOException {
        if (length < 0) {
            throw new IOException("Negative class-file attribute length");
        }
        skipFully(data, length);
    }

    private void skipFully(DataInputStream data, int length) throws IOException {
        if (length < 0) {
            throw new IOException("Negative class-file structure length");
        }
        int remaining = length;
        while (remaining > 0) {
            int skipped = data.skipBytes(remaining);
            if (skipped == 0) {
                if (data.read() < 0) {
                    throw new EOFException("Unexpected end of class file");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private String utf8Value(String[] utf8, int index) throws IOException {
        if (index <= 0 || index >= utf8.length || utf8[index] == null) {
            throw new IOException("Invalid class-file UTF-8 index " + index);
        }
        return utf8[index];
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
            long weight = 128 + stringWeight(binaryName);
            for (Map.Entry<String, MethodLookup> method : methods.entrySet()) {
                weight += 64 + stringWeight(method.getKey())
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
            long weight = 48;
            if (failure != null) {
                weight += stringWeight(failure);
            }
            if (names != null) {
                weight += 32 + 8L * names.size();
                for (String name : names) {
                    weight += stringWeight(name);
                }
            }
            return weight;
        }
    }

    private static final class ParameterLayout {
        private final int parameterCount;
        private final int firstSlot;
        private final int slotLimit;

        private ParameterLayout(int parameterCount, int firstSlot, int slotLimit) {
            this.parameterCount = parameterCount;
            this.firstSlot = firstSlot;
            this.slotLimit = slotLimit;
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
            lookupWeightBytes += 64 + stringWeight(key) + result.estimatedWeight();
            if (lookupWeightBytes > MAX_CLASS_LOOKUP_WEIGHT_BYTES) {
                throw new IOException(
                        "Class parameter metadata exceeds the retained lookup safety limit");
            }
        }
    }

    private static long stringWeight(String value) {
        return value == null ? 0 : 40L + 2L * value.length();
    }
}
