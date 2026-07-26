package example;

import com.facebook.swift.codec.ThriftEnum;
import com.facebook.swift.codec.ThriftEnumValue;

@ThriftEnum
public enum InvalidEnum {
    FIRST;

    @ThriftEnumValue
    public static String getValue() {
        return "bad";
    }
}
