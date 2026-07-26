package example;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;

@ThriftStruct
public class ConflictingIds {
    private String name;

    @ThriftField(1)
    public String getName() {
        return name;
    }

    @ThriftField(2)
    public void setName(String name) {
        this.name = name;
    }
}
