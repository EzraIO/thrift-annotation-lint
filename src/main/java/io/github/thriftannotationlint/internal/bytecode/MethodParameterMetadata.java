package io.github.thriftannotationlint.internal.bytecode;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Retains the distinct class-file parameter-name views used by Swift and Drift. */
final class MethodParameterMetadata {
    private final List<String> paranamerLocalNames = new ArrayList<String>();
    private final Map<Integer, String> localNamesBySlot =
            new LinkedHashMap<Integer, String>();
    private List<String> methodParameterNames;

    void setMethodParameterNames(List<String> names) {
        methodParameterNames = new ArrayList<String>(names);
    }

    int readMethodParameters(
            byte[] attribute,
            String[] utf8,
            ClassFileDataReader dataReader) throws IOException {
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(attribute));
        int count = data.readUnsignedByte();
        List<String> names = new ArrayList<String>();
        for (int index = 0; index < count; index++) {
            int nameIndex = data.readUnsignedShort();
            names.add(nameIndex == 0 ? null : dataReader.utf8Value(utf8, nameIndex));
            data.readUnsignedShort();
        }
        if (data.available() != 0) {
            throw new IOException("Invalid MethodParameters attribute length");
        }
        setMethodParameterNames(names);
        return count;
    }

    void addLocalVariable(String name, int slot) {
        paranamerLocalNames.add(name);
        localNamesBySlot.put(slot, name);
    }

    Lookups resolve(MethodDescriptorParser.Layout layout) {
        ParameterNameLookup swift =
                ClassFileParameterNameParser.classifyLocalVariableNames(
                        paranamerLocalNames, layout.parameterCount);
        ParameterNameLookup drift = driftLookup(layout);
        return new Lookups(swift, drift);
    }

    private ParameterNameLookup driftLookup(
            MethodDescriptorParser.Layout layout) {
        if (layout.parameterCount == 0) {
            return ParameterNameLookup.found(
                    java.util.Collections.<String>emptyList());
        }
        if (hasCompleteMethodParameters(layout.parameterCount)) {
            return ParameterNameLookup.found(methodParameterNames);
        }

        List<String> names = new ArrayList<String>();
        for (int slot : layout.parameterSlots) {
            String name = localNamesBySlot.get(slot);
            if (name == null) {
                return ParameterNameLookup.absent(
                        reflectionFallbackNames(layout.parameterCount));
            }
            names.add(name);
        }
        return ParameterNameLookup.found(names);
    }

    private List<String> reflectionFallbackNames(int parameterCount) {
        List<String> names = new ArrayList<String>();
        for (int index = 0; index < parameterCount; index++) {
            String declaredName = methodParameterNames != null
                    && index < methodParameterNames.size()
                    ? methodParameterNames.get(index)
                    : null;
            names.add(declaredName == null ? "arg" + index : declaredName);
        }
        return names;
    }

    private boolean hasCompleteMethodParameters(int parameterCount) {
        if (methodParameterNames == null || methodParameterNames.size() != parameterCount) {
            return false;
        }
        for (String name : methodParameterNames) {
            if (name == null) {
                return false;
            }
        }
        return true;
    }

    static final class Lookups {
        private final ParameterNameLookup swift;
        private final ParameterNameLookup drift;

        Lookups(
                ParameterNameLookup swift,
                ParameterNameLookup drift) {
            this.swift = swift;
            this.drift = drift;
        }

        ParameterNameLookup swift() {
            return swift;
        }

        ParameterNameLookup drift() {
            return drift;
        }

        long estimatedWeight() {
            return swift.estimatedWeight() + drift.estimatedWeight();
        }
    }
}
