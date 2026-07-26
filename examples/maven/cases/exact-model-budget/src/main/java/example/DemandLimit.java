package example;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;

@ThriftStruct
public class DemandLimit {
    @ThriftField(1)
    public Box<String> first;

    @ThriftField(2)
    public Box<Integer> second;

    @ThriftStruct
    public static class Box<T> {
        @ThriftField(1)
        public T value;
    }
}
