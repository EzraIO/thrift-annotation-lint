package example;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;
import com.facebook.swift.codec.ThriftUnion;

@ThriftStruct
@ThriftUnion
public class InvalidModelAnnotations {
    @ThriftField(1)
    public String value;
}
