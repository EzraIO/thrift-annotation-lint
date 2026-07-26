package example;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;

@ThriftStruct
public class PrivateAnnotatedField {
    @ThriftField(1)
    private String value;
}
