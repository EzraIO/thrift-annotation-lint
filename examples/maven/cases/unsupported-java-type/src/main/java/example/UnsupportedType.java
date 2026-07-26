package example;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;

@ThriftStruct
public class UnsupportedType {
    @ThriftField(1)
    public Object value;
}
