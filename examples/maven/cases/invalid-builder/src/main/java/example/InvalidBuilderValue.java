package example;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;

@ThriftStruct(builder = InvalidBuilderValue.Builder.class)
public class InvalidBuilderValue {
    @ThriftField(1)
    public String getValue() {
        return "value";
    }

    public static class Builder {
        @ThriftField(1)
        public void setValue(String value) {
        }
    }
}
