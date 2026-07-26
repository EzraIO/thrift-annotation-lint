package example;

import com.facebook.swift.codec.ThriftConstructor;
import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;

@ThriftStruct
public final class ValidImmutable {
    private final String name;

    @ThriftConstructor
    public ValidImmutable(@ThriftField(1) String name) {
        this.name = name;
    }

    @ThriftField(1)
    public String getName() {
        return name;
    }
}
