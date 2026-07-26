package example;

import com.facebook.swift.codec.ThriftConstructor;
import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;

@ThriftStruct(builder = BuilderConstructorAdvisory.Builder.class)
public final class BuilderConstructorAdvisory {
    private final String value;

    @ThriftConstructor
    public BuilderConstructorAdvisory(@ThriftField(1) String value) {
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
        public BuilderConstructorAdvisory build() {
            return new BuilderConstructorAdvisory(value);
        }
    }
}
