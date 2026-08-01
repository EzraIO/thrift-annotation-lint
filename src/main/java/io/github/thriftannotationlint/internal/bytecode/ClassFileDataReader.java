package io.github.thriftannotationlint.internal.bytecode;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** Bounded primitive reads shared by class-file parsing stages. */
final class ClassFileDataReader {
    private static final int MAX_CLASS_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ATTRIBUTE_BYTES = 16 * 1024 * 1024;
    private static final int READ_BUFFER_BYTES = 8192;

    byte[] readClassBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(READ_BUFFER_BYTES);
        byte[] buffer = new byte[READ_BUFFER_BYTES];
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

    byte[] readBytes(DataInputStream data, int length) throws IOException {
        if (length < 0 || length > MAX_ATTRIBUTE_BYTES) {
            throw new IOException("Invalid or oversized class-file attribute length " + length);
        }
        byte[] result = new byte[length];
        data.readFully(result);
        return result;
    }

    void skipAttribute(DataInputStream data, int length) throws IOException {
        if (length < 0) {
            throw new IOException("Negative class-file attribute length");
        }
        skipFully(data, length);
    }

    void skipU2Table(DataInputStream data) throws IOException {
        int count = data.readUnsignedShort();
        skipFully(data, count * ClassFileFormat.U2_BYTES);
    }

    void skipU2Values(DataInputStream data, int count) throws IOException {
        skipFully(data, count * ClassFileFormat.U2_BYTES);
    }

    void skipFully(DataInputStream data, int length) throws IOException {
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

    String utf8Value(String[] utf8, int index) throws IOException {
        if (index <= 0 || index >= utf8.length || utf8[index] == null) {
            throw new IOException("Invalid class-file UTF-8 index " + index);
        }
        return utf8[index];
    }
}
