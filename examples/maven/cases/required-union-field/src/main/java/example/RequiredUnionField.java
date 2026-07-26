package example;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftUnion;
import com.facebook.swift.codec.ThriftUnionId;

import static com.facebook.swift.codec.ThriftField.Requiredness.REQUIRED;

@ThriftUnion
public class RequiredUnionField {
    @ThriftUnionId
    public short id;

    @ThriftField(value = 1, requiredness = REQUIRED)
    public String text;
}
