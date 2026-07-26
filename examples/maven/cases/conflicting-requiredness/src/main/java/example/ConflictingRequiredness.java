package example;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;

import static com.facebook.swift.codec.ThriftField.Requiredness.OPTIONAL;
import static com.facebook.swift.codec.ThriftField.Requiredness.REQUIRED;

@ThriftStruct
public class ConflictingRequiredness {
    private String value;

    @ThriftField(value = 1, name = "value", requiredness = OPTIONAL)
    public String getValue() {
        return value;
    }

    @ThriftField(value = 1, name = "value", requiredness = REQUIRED)
    public void setValue(String value) {
        this.value = value;
    }
}
