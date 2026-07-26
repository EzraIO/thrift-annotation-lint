package example;

import com.facebook.swift.codec.ThriftConstructor;
import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftUnion;
import com.facebook.swift.codec.ThriftUnionId;

@ThriftUnion
public class InvalidUnionConstructor {
    @ThriftUnionId
    public short id;

    @ThriftField(1)
    public String text;

    @ThriftField(2)
    public Integer number;

    @ThriftConstructor
    public InvalidUnionConstructor(@ThriftField(1) String text, @ThriftField(2) Integer number) {
    }
}
