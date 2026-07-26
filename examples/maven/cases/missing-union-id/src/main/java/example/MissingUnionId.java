package example;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftUnion;

@ThriftUnion
public class MissingUnionId {
    @ThriftField(1)
    public String text;
}
