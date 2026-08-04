package example;

@io.airlift.drift.annotations.ThriftStruct
public class MixedDriftNamespace {
    @com.facebook.drift.annotations.ThriftField(1)
    public String value;
}
