package example;

import com.facebook.swift.codec.ThriftConstructor;
import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;

@ThriftStruct(builder = ValidBuilder.Builder.class)
public final class ValidBuilder {
    private final String value;

    private ValidBuilder(String value) {
        this.value = value;
    }

    @ThriftField(1)
    public String getValue() {
        return value;
    }

    public static class Builder {
        private String value;

        @ThriftField(1)
        public void setValue(String value) {
            this.value = value;
        }

        @ThriftConstructor
        public ValidBuilder build() {
            return new ValidBuilder(value);
        }
    }
}
