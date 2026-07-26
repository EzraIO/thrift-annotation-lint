package example;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;

@ThriftStruct
public class InvalidLegacyId {
    @ThriftField(-1)
    public String value;
}
