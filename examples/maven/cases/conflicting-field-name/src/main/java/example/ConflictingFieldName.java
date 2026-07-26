package example;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;

@ThriftStruct
public class ConflictingFieldName {
    private String value;

    @ThriftField(value = 1, name = "first")
    public String getValue() {
        return value;
    }

    @ThriftField(value = 1, name = "second")
    public void setValue(String value) {
        this.value = value;
    }
}
