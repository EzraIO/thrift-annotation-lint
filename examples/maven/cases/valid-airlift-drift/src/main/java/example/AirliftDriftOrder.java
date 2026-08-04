package example;

import io.airlift.drift.annotations.ThriftField;
import io.airlift.drift.annotations.ThriftStruct;

import java.util.Optional;

@ThriftStruct
public class AirliftDriftOrder {
    @ThriftField(1)
    public String orderId;

    @ThriftField(2)
    public Optional<AirliftDriftCustomer> customer;
}
