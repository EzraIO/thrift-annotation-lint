package example;

import io.airlift.drift.annotations.ThriftField;
import io.airlift.drift.annotations.ThriftStruct;

@ThriftStruct
public class AirliftDriftCustomer {
    @ThriftField(1)
    public String name;
}
