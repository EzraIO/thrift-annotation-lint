package example;

import com.facebook.swift.codec.ThriftEnum;
import com.facebook.swift.codec.ThriftEnumValue;

@ThriftEnum
public enum ValidEnum {
    FIRST,
    SECOND;

    @ThriftEnumValue
    public int getValue() {
        return ordinal();
    }
}
