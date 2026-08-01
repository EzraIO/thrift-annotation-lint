package io.github.thriftannotationlint.internal.bytecode;

import java.io.IOException;

/** Computes JVM local-variable slots for method parameters. */
final class MethodDescriptorParser {
    Layout layout(String descriptor, boolean isStatic) throws IOException {
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
            slot += array || type == 'L' ? 1 : (type == 'J' || type == 'D' ? 2 : 1);
            index++;
        }
        if (index >= descriptor.length() || descriptor.charAt(index) != ')') {
            throw invalid(descriptor);
        }
        return new Layout(parameterCount, firstSlot, slot);
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

        Layout(int parameterCount, int firstSlot, int slotLimit) {
            this.parameterCount = parameterCount;
            this.firstSlot = firstSlot;
            this.slotLimit = slotLimit;
        }
    }
}
