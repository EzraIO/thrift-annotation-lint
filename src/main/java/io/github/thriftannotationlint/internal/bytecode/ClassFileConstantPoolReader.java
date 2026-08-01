package io.github.thriftannotationlint.internal.bytecode;

import java.io.DataInputStream;
import java.io.IOException;

/** Reads only the UTF-8 entries needed by the parameter-name parser. */
final class ClassFileConstantPoolReader {
    private final ClassFileDataReader dataReader;

    ClassFileConstantPoolReader(ClassFileDataReader dataReader) {
        this.dataReader = dataReader;
    }

    String[] read(DataInputStream data) throws IOException {
        int count = data.readUnsignedShort();
        String[] utf8 = new String[count];
        for (int index = 1; index < count; index++) {
            int tag = data.readUnsignedByte();
            if (tag == ClassFileFormat.CONSTANT_UTF8) {
                utf8[index] = data.readUTF();
            }
            else if (isFourByteEntry(tag)) {
                dataReader.skipFully(data, ClassFileFormat.INTEGER_OR_FLOAT_INFO_BYTES);
            }
            else if (tag == ClassFileFormat.CONSTANT_LONG
                    || tag == ClassFileFormat.CONSTANT_DOUBLE) {
                dataReader.skipFully(data, ClassFileFormat.LONG_OR_DOUBLE_INFO_BYTES);
                index++;
            }
            else if (isTwoByteEntry(tag)) {
                dataReader.skipFully(data, ClassFileFormat.SINGLE_INDEX_INFO_BYTES);
            }
            else if (isReferenceEntry(tag)) {
                dataReader.skipFully(data, ClassFileFormat.REFERENCE_INFO_BYTES);
            }
            else if (tag == ClassFileFormat.CONSTANT_METHOD_HANDLE) {
                dataReader.skipFully(data, ClassFileFormat.METHOD_HANDLE_INFO_BYTES);
            }
            else {
                throw new IOException("Unsupported class-file constant-pool tag " + tag);
            }
        }
        return utf8;
    }

    private boolean isFourByteEntry(int tag) {
        return tag == ClassFileFormat.CONSTANT_INTEGER
                || tag == ClassFileFormat.CONSTANT_FLOAT;
    }

    private boolean isTwoByteEntry(int tag) {
        return tag == ClassFileFormat.CONSTANT_CLASS
                || tag == ClassFileFormat.CONSTANT_STRING
                || tag == ClassFileFormat.CONSTANT_METHOD_TYPE
                || tag == ClassFileFormat.CONSTANT_MODULE
                || tag == ClassFileFormat.CONSTANT_PACKAGE;
    }

    private boolean isReferenceEntry(int tag) {
        return tag == ClassFileFormat.CONSTANT_FIELD_REF
                || tag == ClassFileFormat.CONSTANT_METHOD_REF
                || tag == ClassFileFormat.CONSTANT_INTERFACE_METHOD_REF
                || tag == ClassFileFormat.CONSTANT_NAME_AND_TYPE
                || tag == ClassFileFormat.CONSTANT_DYNAMIC
                || tag == ClassFileFormat.CONSTANT_INVOKE_DYNAMIC;
    }
}
