package example;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftUnion;
import com.facebook.swift.codec.ThriftUnionId;

@ThriftUnion
public class ZeroIdUnion {
    @ThriftUnionId
    public short id;

    @ThriftField(0)
    public int value;
}
