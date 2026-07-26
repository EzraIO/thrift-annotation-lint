package example;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;

@ThriftStruct
public class ReadOnlyValue {
    @ThriftField(1)
    public String getValue() {
        return "value";
    }
}
