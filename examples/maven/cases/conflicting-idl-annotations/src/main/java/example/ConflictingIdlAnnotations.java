package example;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftIdlAnnotation;
import com.facebook.swift.codec.ThriftStruct;

@ThriftStruct
public class ConflictingIdlAnnotations {
    private String value;

    @ThriftField(value = 1, name = "value",
            idlAnnotations = @ThriftIdlAnnotation(key = "format", value = "a"))
    public String getValue() {
        return value;
    }

    @ThriftField(value = 1, name = "value",
            idlAnnotations = @ThriftIdlAnnotation(key = "format", value = "b"))
    public void setValue(String value) {
        this.value = value;
    }
}
