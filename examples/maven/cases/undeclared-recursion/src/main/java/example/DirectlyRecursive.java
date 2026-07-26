package example;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;

@ThriftStruct
public class DirectlyRecursive {
    @ThriftField(1)
    public DirectlyRecursive next;
}
