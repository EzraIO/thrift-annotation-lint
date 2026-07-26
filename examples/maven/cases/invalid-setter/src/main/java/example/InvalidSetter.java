package example;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;

@ThriftStruct
public class InvalidSetter {
    @ThriftField(1)
    public void setName() {
    }
}
