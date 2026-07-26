package example;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;

@ThriftStruct
public class DuplicateIds {
    @ThriftField(7)
    public String first;

    @ThriftField(7)
    public String second;
}
