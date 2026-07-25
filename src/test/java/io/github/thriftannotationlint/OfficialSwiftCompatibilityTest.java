package io.github.thriftannotationlint;

import com.facebook.swift.codec.ThriftCodecManager;
import com.facebook.swift.codec.ThriftConstructor;
import com.facebook.swift.codec.ThriftEnum;
import com.facebook.swift.codec.ThriftEnumValue;
import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;
import com.facebook.swift.codec.ThriftUnion;
import com.facebook.swift.codec.ThriftUnionId;
import com.facebook.swift.codec.internal.reflection.ReflectionThriftCodecFactory;
import com.facebook.swift.codec.metadata.ThriftCatalog;
import com.facebook.swift.codec.metadata.ThriftFieldMetadata;
import com.facebook.swift.codec.metadata.ThriftMethodExtractor;
import com.facebook.swift.codec.metadata.ThriftStructMetadata;
import org.apache.thrift.protocol.TCompactProtocol;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import javax.inject.Named;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks representative processor assumptions to the official Swift 0.23.1 implementation. */
class OfficialSwiftCompatibilityTest {
    @Test
    void officialMetadataRecognizesValidStructBuilderAndUnionModels() {
        ThriftCatalog catalog = new ThriftCatalog();

        ThriftStructMetadata mutable = catalog.getThriftStructMetadata(MutableValue.class);
        ThriftStructMetadata built = catalog.getThriftStructMetadata(BuiltValue.class);
        ThriftStructMetadata union = catalog.getThriftStructMetadata(TextUnion.class);

        assertTrue(mutable.isStruct());
        assertNotNull(mutable.getField(1));
        assertEquals(BuiltValue.Builder.class, built.getBuilderClass());
        assertTrue(union.isUnion());
        assertNotNull(union.getField(Short.MIN_VALUE));
    }

    @Test
    void officialMetadataAllowsReadOnlyFieldsThatThriftAnnotationLintDeliberatelyRejects() {
        ThriftStructMetadata metadata =
                new ThriftCatalog().getThriftStructMetadata(ReadOnlyValue.class);
        ThriftFieldMetadata field = metadata.getField(1);

        assertNotNull(field);
        assertTrue(field.isReadOnly());
        assertFalse(field.isWriteOnly());

        ThriftFieldMetadata genericOverrideField =
                new ThriftCatalog().getThriftStructMetadata(GenericOverrideValue.class)
                        .getField(1);
        assertNotNull(genericOverrideField);
        assertTrue(genericOverrideField.isReadOnly());
        assertFalse(genericOverrideField.isWriteOnly());
    }

    @Test
    void officialMetadataMatchesLegacyAndParameterNameInferenceBoundaries() {
        assertNotNull(
                new ThriftCatalog().getThriftStructMetadata(MixedLegacyValue.class)
                        .getField(-1));
        assertThrows(
                RuntimeException.class,
                () -> new ThriftCatalog().getThriftStructMetadata(ArgNameConflict.class));
        assertThrows(
                RuntimeException.class,
                () -> new ThriftCatalog().getThriftStructMetadata(PartialAnnotationNames.class));
        assertThrows(
                RuntimeException.class,
                () -> new ThriftCatalog().getThriftStructMetadata(NamedFirstConflict.class));
        assertNotNull(
                new ThriftCatalog().getThriftStructMetadata(ThriftFieldFirstName.class)
                        .getField(1));
        assertNotNull(
                new ThriftCatalog().getThriftStructMetadata(NamedInferredValue.class)
                        .getField(1));
        assertNotNull(
                new ThriftCatalog().getThriftStructMetadata(BareNamedValue.class)
                        .getField(1));
        assertThrows(
                RuntimeException.class,
                () -> new ThriftCatalog().getThriftStructMetadata(EmptyNamedConflict.class));
    }

    @Test
    void officialCatalogClassifiesContainersBeforeStructAnnotations() {
        assertEquals(
                "LIST",
                new ThriftCatalog().getThriftType(AnnotatedStringList.class)
                        .getProtocolType().name());
        assertEquals(
                "ENUM",
                new ThriftCatalog().getThriftType(IterableEnum.class)
                        .getProtocolType().name());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThriftCatalog().getThriftType(AnnotatedObjectList.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThriftCatalog().getThriftType(IterableBadEnum.class));
    }

    @Test
    void officialMetadataRetainsOnlyOneAmbiguousExtractorAndUnionSetter() {
        ThriftFieldMetadata structField = new ThriftCatalog()
                .getThriftStructMetadata(AmbiguousReaders.class)
                .getField(1);
        ThriftFieldMetadata getterWinsField = new ThriftCatalog()
                .getThriftStructMetadata(GetterOverridesFields.class)
                .getField(1);
        ThriftFieldMetadata unionField = new ThriftCatalog()
                .getThriftStructMetadata(AmbiguousUnionSetters.class)
                .getField(1);

        assertTrue(structField.getExtraction().isPresent());
        assertTrue(structField.getExtraction().get() instanceof ThriftMethodExtractor);
        String extractor = ((ThriftMethodExtractor) structField.getExtraction().get())
                .getMethod().getName();
        assertTrue(Arrays.asList("getFirst", "getSecond").contains(extractor));
        assertTrue(getterWinsField.getExtraction().isPresent());
        assertTrue(getterWinsField.getExtraction().get() instanceof ThriftMethodExtractor);
        assertEquals(
                "getValue",
                ((ThriftMethodExtractor) getterWinsField.getExtraction().get())
                        .getMethod().getName());
        assertTrue(unionField.getMethodInjection().isPresent());
        String injection = unionField.getMethodInjection().get().getMethod().getName();
        assertTrue(Arrays.asList("setFirst", "setSecond").contains(injection));
    }

    @Test
    void officialUnionMetadataRejectsBothDiscriminatorNameInferenceCollisions() {
        assertThrows(
                RuntimeException.class,
                () -> new ThriftCatalog().getThriftStructMetadata(ReservedUnionName.class));
        assertThrows(
                RuntimeException.class,
                () -> new ThriftCatalog().getThriftStructMetadata(ExtractedUnionName.class));
    }

    @Test
    void officialDefaultCodecRejectsBoxedUnionDiscriminators() {
        BoxedIdUnion value = new BoxedIdUnion();
        value.id = 1;
        value.text = "text";

        Throwable failure = assertThrows(
                Throwable.class,
                () -> new ThriftCodecManager().write(
                        value,
                        new ByteArrayOutputStream(),
                        new TCompactProtocol.Factory()));
        assertTrue(hasCause(failure, VerifyError.class), failure.toString());
    }

    @Test
    void officialDefaultCodecConfusesUnionFieldZeroWithAnEmptyPayload() {
        ThriftCodecManager manager = new ThriftCodecManager();
        TCompactProtocol.Factory protocols = new TCompactProtocol.Factory();
        ByteArrayOutputStream emptyBytes = new ByteArrayOutputStream();
        manager.write(new EmptyValue(), emptyBytes, protocols);

        ZeroIdUnion decoded = manager.read(
                emptyBytes.toByteArray(), ZeroIdUnion.class, protocols);
        ByteArrayOutputStream reencoded = new ByteArrayOutputStream();
        manager.write(decoded, reencoded, protocols);

        assertEquals(0, decoded.id);
        assertTrue(reencoded.size() > emptyBytes.size());
    }

    @Test
    void officialEnumMetadataRejectsAGenericValueMethod() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThriftCatalog().getThriftEnumMetadata(GenericValue.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThriftCatalog().getThriftEnumMetadata(GenericInheritedValue.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThriftCatalog().getThriftEnumMetadata(BridgeEnumValue.class));
    }

    @Test
    void officialCodecRoundTripsBuilderAndUnionModels() {
        ThriftCodecManager manager = new ThriftCodecManager();
        TCompactProtocol.Factory protocols = new TCompactProtocol.Factory();

        BuiltValue originalValue = new BuiltValue("value");
        ByteArrayOutputStream valueBytes = new ByteArrayOutputStream();
        manager.write(originalValue, valueBytes, protocols);
        BuiltValue decodedValue =
                manager.read(valueBytes.toByteArray(), BuiltValue.class, protocols);

        TextUnion originalUnion = new TextUnion();
        originalUnion.id = 1;
        originalUnion.text = "text";
        ByteArrayOutputStream unionBytes = new ByteArrayOutputStream();
        manager.write(originalUnion, unionBytes, protocols);
        TextUnion decodedUnion =
                manager.read(unionBytes.toByteArray(), TextUnion.class, protocols);

        assertEquals("value", decodedValue.getValue());
        assertEquals(1, decodedUnion.id);
        assertEquals("text", decodedUnion.text);
    }

    @Test
    void officialCodecRejectsAFieldDiscriminatorOnASeparateUnionBuilder() {
        ThriftCodecManager manager = new ThriftCodecManager();
        TCompactProtocol.Factory protocols = new TCompactProtocol.Factory();

        TextUnion sourceUnion = new TextUnion();
        sourceUnion.id = 1;
        sourceUnion.text = "text";
        ByteArrayOutputStream unionBytes = new ByteArrayOutputStream();
        manager.write(sourceUnion, unionBytes, protocols);
        Throwable unsafeUnionFailure = assertThrows(
                Throwable.class,
                () -> manager.read(
                        unionBytes.toByteArray(), UnsafeBuilderUnion.class, protocols));
        assertTrue(hasCause(unsafeUnionFailure, VerifyError.class),
                unsafeUnionFailure.toString());

    }

    @Test
    void officialCodecRequiresAConstructionPathForEveryUnionVariant() {
        CompleteNumberUnion source = new CompleteNumberUnion();
        source.id = 2;
        source.number = 7;
        TCompactProtocol.Factory protocols = new TCompactProtocol.Factory();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new ThriftCodecManager().write(source, bytes, protocols);

        assertThrows(
                RuntimeException.class,
                () -> new ThriftCodecManager().read(
                        bytes.toByteArray(), IncompleteConstructorUnion.class, protocols));
    }

    @Test
    void officialCodecsRejectConcreteContainerInjectionTargets() {
        ListValue source = new ListValue();
        source.values = Arrays.asList("one", "two");
        TCompactProtocol.Factory protocols = new TCompactProtocol.Factory();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new ThriftCodecManager().write(source, bytes, protocols);

        Throwable compilerFailure = assertThrows(
                Throwable.class,
                () -> new ThriftCodecManager().read(
                        bytes.toByteArray(), ConcreteListWriterValue.class, protocols));
        assertTrue(hasCause(compilerFailure, VerifyError.class),
                compilerFailure.toString());

        Throwable reflectionFailure = assertThrows(
                Throwable.class,
                () -> new ThriftCodecManager(new ReflectionThriftCodecFactory()).read(
                        bytes.toByteArray(), ConcreteListWriterValue.class, protocols));
        assertTrue(hasCause(reflectionFailure, IllegalArgumentException.class),
                reflectionFailure.toString());
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void officialRuntimeFailsForExecutableTypeVariablesAndHiddenDeclaringTypes() {
        assertThrows(
                RuntimeException.class,
                () -> new ThriftCatalog().getThriftStructMetadata(GenericMethodValue.class));

        ThriftCodecManager manager = new ThriftCodecManager();
        assertThrows(
                IllegalAccessError.class,
                () -> manager.write(
                        new InaccessibleInheritedValue(),
                        new ByteArrayOutputStream(),
                        new TCompactProtocol.Factory()));
    }

    @Test
    void officialRuntimeKeepsTypeVariablesAndWildcardsAsExactStructRequests()
            throws NoSuchMethodException, NoSuchFieldException {
        Type variable = ExactTypeRequests.class.getMethod("getValue").getGenericReturnType();
        Type list = ExactTypeRequests.class.getField("values").getGenericType();
        Type wildcard = ((ParameterizedType) list).getActualTypeArguments()[0];
        ThriftCatalog catalog = new ThriftCatalog();

        assertThrows(RuntimeException.class, () -> catalog.getThriftStructMetadata(variable));
        assertThrows(RuntimeException.class, () -> catalog.getThriftStructMetadata(wildcard));
    }

    @ThriftStruct
    public static class MutableValue {
        @ThriftField(1)
        public String value;
    }

    @ThriftStruct
    public static class ReadOnlyValue {
        @ThriftField(1)
        public String getValue() {
            return "value";
        }
    }

    public interface GenericWriter<T> {
        @ThriftField(1)
        void setValue(T value);
    }

    @ThriftStruct
    public static class GenericOverrideValue implements GenericWriter<String> {
        @Override
        public void setValue(String value) {
        }

        @ThriftField(1)
        public String getValue() {
            return "value";
        }
    }

    @ThriftStruct
    public static class MixedLegacyValue {
        @ThriftField(value = -1, isLegacyId = true)
        public String getValue() {
            return "value";
        }

        @ThriftField
        public void setValue(String value) {
        }
    }

    @ThriftStruct
    public static class ArgNameConflict {
        @ThriftField(2)
        public String arg0;

        @ThriftField(1)
        public String value;

        @ThriftConstructor
        public ArgNameConflict(@ThriftField(1) String arg0) {
        }
    }

    @ThriftStruct
    public static class PartialAnnotationNames {
        @ThriftField(3)
        public String sourceLeft;

        @ThriftField(1)
        public String left;

        @ThriftField(2)
        public String sourceRight;

        @ThriftField
        public void inject(
                @ThriftField(value = 1, name = "left") String sourceLeft,
                @ThriftField(2) String sourceRight) {
        }
    }

    @ThriftStruct
    public static class NamedFirstConflict {
        @ThriftField(2)
        public String arg0;

        @ThriftField(1)
        public String value;

        @ThriftConstructor
        public NamedFirstConflict(
                @Named("arg0") @ThriftField(1) String source) {
        }
    }

    @ThriftStruct
    public static class ThriftFieldFirstName {
        @ThriftField(2)
        public String arg0;

        @ThriftField(1)
        public String value;

        @ThriftConstructor
        public ThriftFieldFirstName(
                @ThriftField(value = 1, name = "value")
                @Named("arg0") String source) {
        }
    }

    @ThriftStruct
    public static class NamedInferredValue {
        @ThriftConstructor
        public NamedInferredValue(
                @Named("value") @ThriftField String source) {
        }

        @ThriftField(1)
        public String getValue() {
            return "value";
        }
    }

    @ThriftStruct
    public static class BareNamedValue {
        @ThriftField(2)
        public String arg0;

        @ThriftField(1)
        public String value;

        @ThriftConstructor
        public BareNamedValue(
                @Named @ThriftField(1) String source) {
        }
    }

    @ThriftStruct
    public static class EmptyNamedConflict {
        @ThriftField(1)
        public String left;

        @ThriftField(2)
        public String right;

        @ThriftConstructor
        public EmptyNamedConflict(
                @Named("") @ThriftField(1) String first,
                @Named("") @ThriftField(2) String second) {
        }
    }

    @ThriftStruct(builder = BuiltValue.Builder.class)
    public static class BuiltValue {
        private final String value;

        private BuiltValue(String value) {
            this.value = value;
        }

        @ThriftField(1)
        public String getValue() {
            return value;
        }

        public static class Builder {
            private String value;

            @ThriftField(1)
            public void setValue(String value) {
                this.value = value;
            }

            @ThriftConstructor
            public BuiltValue build() {
                return new BuiltValue(value);
            }
        }
    }

    @ThriftUnion
    public static class TextUnion {
        @ThriftUnionId
        public short id;

        @ThriftField(1)
        public String text;
    }

    @ThriftUnion
    public static class BoxedIdUnion {
        @ThriftUnionId
        public Short id;

        @ThriftField(1)
        public String text;
    }

    @ThriftStruct
    public static class EmptyValue {
    }

    @ThriftUnion
    public static class ZeroIdUnion {
        @ThriftUnionId
        public short id;

        @ThriftField(0)
        public int value;
    }

    @ThriftUnion
    public static class CompleteNumberUnion {
        @ThriftUnionId
        public short id;

        @ThriftField(1)
        public String text;

        @ThriftField(2)
        public Integer number;
    }

    @ThriftUnion
    public static class IncompleteConstructorUnion {
        @ThriftUnionId
        public short id;

        @ThriftField(1)
        public String text;

        @ThriftField(2)
        public Integer number;

        public IncompleteConstructorUnion() {
        }

        @ThriftConstructor
        public IncompleteConstructorUnion(
                @ThriftField(value = 1, name = "text") String text) {
            this.text = text;
        }
    }

    @ThriftUnion
    public static class ReservedUnionName {
        @ThriftUnionId
        public short id;

        @ThriftField(value = 1, name = "_union_id")
        public String value;
    }

    @ThriftUnion
    public static class ExtractedUnionName {
        @ThriftUnionId
        public short type;

        @ThriftField(value = 1, name = "payload")
        public String getType() {
            return "";
        }

        @ThriftField(value = 1, name = "payload")
        public void setType(String value) {
        }
    }

    @ThriftUnion(builder = UnsafeBuilderUnion.Builder.class)
    public static class UnsafeBuilderUnion {
        @ThriftUnionId
        public short id;

        private final String value;

        private UnsafeBuilderUnion(String value) {
            this.value = value;
        }

        @ThriftField(1)
        public String getValue() {
            return value;
        }

        public static class Builder {
            public Builder() {
            }

            @ThriftConstructor
            public UnsafeBuilderUnion build(@ThriftField(1) String value) {
                return new UnsafeBuilderUnion(value);
            }
        }
    }

    @ThriftStruct
    public static class AnnotatedStringList extends ArrayList<String> {
        private static final long serialVersionUID = 1L;
    }

    @ThriftStruct
    public static class AnnotatedObjectList extends ArrayList<Object> {
        private static final long serialVersionUID = 1L;
    }

    @ThriftStruct
    public static class AmbiguousReaders {
        @ThriftField(value = 1, name = "value")
        public String getFirst() {
            return "first";
        }

        @ThriftField(value = 1, name = "value")
        public String getSecond() {
            return "second";
        }

        @ThriftField(value = 1, name = "value")
        public void setValue(String value) {
        }
    }

    @ThriftStruct
    public static class GetterOverridesFields {
        @ThriftField(value = 1, name = "value")
        public String first;

        @ThriftField(value = 1, name = "value")
        public String second;

        @ThriftField(value = 1, name = "value")
        public String getValue() {
            return first;
        }

        @ThriftField(value = 1, name = "value")
        public void setValue(String value) {
            first = value;
        }
    }

    @ThriftUnion
    public static class AmbiguousUnionSetters {
        @ThriftUnionId
        public short id;

        @ThriftField(value = 1, name = "value")
        public String getValue() {
            return null;
        }

        @ThriftField(value = 1, name = "value")
        public void setFirst(String value) {
        }

        @ThriftField(value = 1, name = "value")
        public void setSecond(String value) {
        }
    }

    @ThriftStruct
    public static class ListValue {
        @ThriftField(1)
        public List<String> values;
    }

    @ThriftStruct
    public static class ConcreteListWriterValue {
        private List<String> values;

        @ThriftField(1)
        public List<String> getValues() {
            return values;
        }

        @ThriftField(1)
        public void setValues(LinkedList<String> values) {
            this.values = values;
        }
    }

    public enum IterableBadEnum implements Iterable<String> {
        FIRST;

        @ThriftEnumValue
        public long getValue() {
            return 1L;
        }

        @Override
        public Iterator<String> iterator() {
            return Collections.<String>emptyList().iterator();
        }
    }

    public enum IterableEnum implements Iterable<String> {
        FIRST;

        @Override
        public Iterator<String> iterator() {
            return Collections.<String>emptyList().iterator();
        }
    }

    @ThriftStruct
    public static class GenericMethodValue {
        @ThriftField(1)
        public <T> T getValue() {
            return null;
        }

        @ThriftField(1)
        public <T> void setValue(T value) {
        }
    }

    public enum GenericValue {
        FIRST;

        @ThriftEnumValue
        public <T> int getValue() {
            return 1;
        }
    }

    public interface GenericEnumValueProvider<T> {
        @ThriftEnumValue
        default T getValue() {
            return null;
        }
    }

    public enum GenericInheritedValue implements GenericEnumValueProvider<Integer> {
        FIRST
    }

    public interface BridgeEnumValueProvider<T> {
        T getValue();
    }

    @ThriftEnum
    public enum BridgeEnumValue implements BridgeEnumValueProvider<Integer> {
        FIRST;

        @Override
        @ThriftEnumValue
        public Integer getValue() {
            return 1;
        }
    }

    @ThriftStruct(builder = GenericBuilt.Builder.class)
    public static class GenericBuilt<T> {
        @ThriftField(1)
        public T getValue() {
            return null;
        }

        public static class Builder<T> {
            @ThriftField(1)
            public void setValue(T value) {
            }

            @ThriftConstructor
            public GenericBuilt<T> build() {
                return new GenericBuilt<T>();
            }
        }
    }

    public static class ExactTypeRequests {
        public <T extends GenericBuilt<String>> T getValue() {
            return null;
        }

        public List<? extends GenericBuilt<String>> values;
    }

    static class HiddenInheritedBase {
        @ThriftField(1)
        public String value = "value";
    }

    @ThriftStruct
    public static class InaccessibleInheritedValue extends HiddenInheritedBase {
    }
}
