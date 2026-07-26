package example;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;

import java.util.List;
import java.util.Map;
import java.util.Set;

@ThriftStruct
public class ValidContainers {
    @ThriftField(1)
    public List<Map<String, Set<Nested>>> values;

    @ThriftStruct
    public static class Nested {
        @ThriftField(1)
        public long id;
    }
}
