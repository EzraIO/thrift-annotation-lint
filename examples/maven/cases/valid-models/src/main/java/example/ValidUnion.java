package example;

import com.facebook.swift.codec.ThriftConstructor;
import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftUnion;
import com.facebook.swift.codec.ThriftUnionId;

@ThriftUnion
public class ValidUnion {
    @ThriftUnionId
    public short id;

    @ThriftConstructor
    public ValidUnion() {
    }

    @ThriftField(1)
    public String text;

    @ThriftField(2)
    public Integer number;
}
