package example;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;

@ThriftStruct
public class ConflictingTypes {
    @ThriftField(1)
    public String getValue() {
        return "";
    }

    @ThriftField(1)
    public void setValue(Integer value) {
    }
}
