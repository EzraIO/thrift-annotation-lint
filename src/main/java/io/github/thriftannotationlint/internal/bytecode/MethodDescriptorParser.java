package io.github.thriftannotationlint.internal.bytecode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Computes JVM local-variable slots for method parameters. */
final class MethodDescriptorParser {
    private static final int STATIC_FIRST_PARAMETER_SLOT = 0;
    private static final int INSTANCE_FIRST_PARAMETER_SLOT = 1;
    private static final int FIRST_PARAMETER_DESCRIPTOR_INDEX = 1;
    private static final int SINGLE_SLOT_WIDTH = 1;
    private static final int WIDE_SLOT_WIDTH = 2;

    Layout layout(String descriptor, boolean isStatic) throws IOException {
        int parameterCount = 0;
        int firstSlot = isStatic
                ? STATIC_FIRST_PARAMETER_SLOT
                : INSTANCE_FIRST_PARAMETER_SLOT;
        int slot = firstSlot;
        List<Integer> parameterSlots = new ArrayList<Integer>();
        int index = FIRST_PARAMETER_DESCRIPTOR_INDEX;
        while (index < descriptor.length() && descriptor.charAt(index) != ')') {
            parameterCount++;
            parameterSlots.add(slot);
            char type = descriptor.charAt(index);
            boolean array = false;
            while (type == '[') {
                array = true;
                index++;
                if (index >= descriptor.length()) {
                    throw invalid(descriptor);
                }
                type = descriptor.charAt(index);
            }
            if (type == 'L') {
                index = descriptor.indexOf(';', index);
                if (index < 0) {
                    throw invalid(descriptor);
                }
            }
            slot += slotWidth(type, array);
            index++;
        }
        if (index >= descriptor.length() || descriptor.charAt(index) != ')') {
            throw invalid(descriptor);
        }
        int[] slots = new int[parameterSlots.size()];
        for (int parameterIndex = 0; parameterIndex < parameterSlots.size(); parameterIndex++) {
            slots[parameterIndex] = parameterSlots.get(parameterIndex);
        }
        return new Layout(parameterCount, firstSlot, slot, slots);
    }

    private int slotWidth(char type, boolean array) {
        if (!array && (type == 'J' || type == 'D')) {
            return WIDE_SLOT_WIDTH;
        }
        return SINGLE_SLOT_WIDTH;
    }

    String parameters(String descriptor) throws IOException {
        int end = descriptor.indexOf(')');
        if (descriptor.isEmpty() || descriptor.charAt(0) != '(' || end < 0) {
            throw invalid(descriptor);
        }
        return descriptor.substring(0, end + 1);
    }

    private IOException invalid(String descriptor) {
        return new IOException("Invalid method descriptor " + descriptor);
    }

    static final class Layout {
        final int parameterCount;
        final int firstSlot;
        final int slotLimit;
        final int[] parameterSlots;

        Layout(int parameterCount, int firstSlot, int slotLimit, int[] parameterSlots) {
            this.parameterCount = parameterCount;
            this.firstSlot = firstSlot;
            this.slotLimit = slotLimit;
            this.parameterSlots = parameterSlots.clone();
        }
    }
}
