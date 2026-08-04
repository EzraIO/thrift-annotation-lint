package example;

import com.facebook.drift.annotations.ThriftConstructor;
import com.facebook.drift.annotations.ThriftField;
import com.facebook.drift.annotations.ThriftStruct;

@ThriftStruct
public class PrestoDriftEvent {
    private final String eventId;

    @ThriftConstructor
    public PrestoDriftEvent(@ThriftField(value = 1, name = "eventId") String eventId) {
        this.eventId = eventId;
    }

    @ThriftField(1)
    public String getEventId() {
        return eventId;
    }
}
