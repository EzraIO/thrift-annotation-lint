package example;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;

import static com.facebook.swift.codec.ThriftField.Recursiveness.TRUE;
import static com.facebook.swift.codec.ThriftField.Requiredness.OPTIONAL;

@ThriftStruct
public class ValidRecursive {
    @ThriftField(value = 1, isRecursive = TRUE, requiredness = OPTIONAL)
    public ValidRecursive next;
}
