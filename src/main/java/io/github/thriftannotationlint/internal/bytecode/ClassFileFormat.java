package io.github.thriftannotationlint.internal.bytecode;

/** Named JVM class-file constants used by the bounded parser. */
final class ClassFileFormat {
    static final int MAGIC = 0xCAFEBABE;
    static final int ACCESS_STATIC = 0x0008;
    static final int MAX_CODE_LENGTH = 65535;
    static final int U2_BYTES = 2;
    static final int EXCEPTION_TABLE_ENTRY_BYTES = 8;
    static final int CLASS_VERSION_U2_FIELDS = 2;
    static final int CLASS_IDENTITY_U2_FIELDS = 3;
    static final int MEMBER_HEADER_U2_FIELDS = 3;
    static final int CODE_HEADER_U2_FIELDS = 2;

    static final int INTEGER_OR_FLOAT_INFO_BYTES = 4;
    static final int LONG_OR_DOUBLE_INFO_BYTES = 8;
    static final int SINGLE_INDEX_INFO_BYTES = 2;
    static final int REFERENCE_INFO_BYTES = 4;
    static final int METHOD_HANDLE_INFO_BYTES = 3;

    static final int CONSTANT_UTF8 = 1;
    static final int CONSTANT_INTEGER = 3;
    static final int CONSTANT_FLOAT = 4;
    static final int CONSTANT_LONG = 5;
    static final int CONSTANT_DOUBLE = 6;
    static final int CONSTANT_CLASS = 7;
    static final int CONSTANT_STRING = 8;
    static final int CONSTANT_FIELD_REF = 9;
    static final int CONSTANT_METHOD_REF = 10;
    static final int CONSTANT_INTERFACE_METHOD_REF = 11;
    static final int CONSTANT_NAME_AND_TYPE = 12;
    static final int CONSTANT_METHOD_HANDLE = 15;
    static final int CONSTANT_METHOD_TYPE = 16;
    static final int CONSTANT_DYNAMIC = 17;
    static final int CONSTANT_INVOKE_DYNAMIC = 18;
    static final int CONSTANT_MODULE = 19;
    static final int CONSTANT_PACKAGE = 20;

    private ClassFileFormat() {
    }
}
