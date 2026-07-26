package example;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;

@ThriftStruct
public class ValidStruct {
    @ThriftField(1)
    public String name;
}
