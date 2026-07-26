package example;

import com.facebook.swift.codec.ThriftConstructor;
import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;

@ThriftStruct
public class MultipleConstructors {
    @ThriftConstructor
    public MultipleConstructors() {
    }

    @ThriftConstructor
    public MultipleConstructors(@ThriftField(1) String value) {
    }

    @ThriftField(1)
    public String value;
}
