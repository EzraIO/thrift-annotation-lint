package io.github.thriftannotationlint;

import org.junit.jupiter.api.Test;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static io.github.thriftannotationlint.CompilerTestSupport.compileWithAdditionalProcessor;
import static io.github.thriftannotationlint.CompilerTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class TypeSystemInspectorTest {
    @Test
    void preservesCatalogPrecedenceIdentitiesAndCanonicalShapesBehindTheFacade() {
        TypeSystemProbe probe = new TypeSystemProbe();

        CompilerTestSupport.CompilationResult result = compileWithAdditionalProcessor(
                probe,
                source("example.TypeFixture",
                        "package example;",
                        "import java.util.AbstractMap;",
                        "import java.util.Iterator;",
                        "import java.util.List;",
                        "import java.util.Map;",
                        "import java.util.Set;",
                        "public class TypeFixture {",
                        "  public abstract static class MapAndIterable",
                        "      extends AbstractMap<String, Integer> implements Iterable<Long> {}",
                        "  public abstract static class SetValue implements Set<String> {}",
                        "  public enum IterableEnum implements Iterable<String> {",
                        "    VALUE;",
                        "    public Iterator<String> iterator() { return null; }",
                        "  }",
                        "  public static class Owner<T> { public class Inner<U> {} }",
                        "  MapAndIterable mapAndIterable;",
                        "  SetValue setValue;",
                        "  IterableEnum iterableEnum;",
                        "  List<String> list;",
                        "  Iterable<String> iterable;",
                        "  List<? extends String[]> wildcardArray;",
                        "  Owner<String>.Inner<Integer> owned;",
                        "  List rawList;",
                        "  String[] objectArray;",
                        "  int[] primitiveArray;",
                        "  public static class Generic<T> {",
                        "    T modelValue;",
                        "    <M extends List<String>> void consume(M methodValue) {}",
                        "  }",
                        "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
        assertEquals("MAP<java.lang.String,java.lang.Integer>", probe.value("map.normalized"));
        assertEquals("SET<java.lang.String>", probe.value("set.normalized"));
        assertEquals("ENUM:example.TypeFixture.IterableEnum", probe.value("enum.normalized"));
        assertEquals("java.util.Map", probe.value("map.container"));
        assertEquals("java.util.Set", probe.value("set.container"));
        assertNull(probe.value("enum.container"));
        assertEquals(
                "JAVA:java.util.List<JAVA_EXTENDS<JAVA_ARRAY<JAVA:java.lang.String>>>",
                probe.value("wildcard.identity"));
        assertEquals(
                "JAVA:example.TypeFixture.Owner.Inner<"
                        + "JAVA_OWNER<JAVA:example.TypeFixture.Owner<JAVA:java.lang.String>>,"
                        + "JAVA:java.lang.Integer>",
                probe.value("owner.identity"));
        assertEquals("DEFERRED_TYPE_VARIABLE", probe.value("model.normalized"));
        assertEquals("LIST<java.lang.String>", probe.value("method.normalized"));
        assertEquals("true", probe.value("model.variable"));
        assertEquals("false", probe.value("method.variable"));
        assertEquals("false", probe.value("raw.supported"));
        assertEquals("false", probe.value("objectArray.supported"));
        assertEquals("true", probe.value("primitiveArray.supported"));
        assertEquals("true", probe.value("list.provides"));
        assertEquals("true", probe.value("list.accepts"));
        assertEquals("false", probe.value("iterable.provides"));
        assertEquals("true", probe.value("iterable.accepts"));
        assertEquals("true", probe.value("enum.provides"));
        assertEquals("true", probe.value("enum.accepts"));
        assertEquals("example.TypeFixture.IterableEnum", probe.value("enum.canonical"));
        assertEquals("true", probe.value("nested.deferred.compatible"));
        assertEquals("false", probe.value("container.kind.compatible"));
        assertEquals("false", probe.value("malformed.compatible"));
    }

    private static final class TypeSystemProbe extends AbstractProcessor {
        private final Map<String, String> values = new LinkedHashMap<String, String>();
        private boolean inspected;

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Collections.singleton("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(
                Set<? extends TypeElement> annotations,
                RoundEnvironment roundEnvironment) {
            if (inspected || roundEnvironment.processingOver()) {
                return false;
            }
            TypeElement fixture = processingEnv.getElementUtils()
                    .getTypeElement("example.TypeFixture");
            if (fixture == null) {
                return false;
            }
            inspected = true;

            SwiftTypeInspector inspector = new SwiftTypeInspector(
                    processingEnv.getTypeUtils(), processingEnv.getElementUtils());
            TypeMirror map = field(fixture, "mapAndIterable").asType();
            TypeMirror set = field(fixture, "setValue").asType();
            TypeMirror enumType = field(fixture, "iterableEnum").asType();
            TypeMirror list = field(fixture, "list").asType();
            TypeMirror iterable = field(fixture, "iterable").asType();
            TypeMirror wildcard = field(fixture, "wildcardArray").asType();
            TypeMirror owned = field(fixture, "owned").asType();
            TypeMirror rawList = field(fixture, "rawList").asType();
            TypeMirror objectArray = field(fixture, "objectArray").asType();
            TypeMirror primitiveArray = field(fixture, "primitiveArray").asType();
            TypeElement generic = nestedType(fixture, "Generic");
            TypeMirror modelVariable = field(generic, "modelValue").asType();
            TypeMirror methodVariable = method(generic, "consume")
                    .getParameters().get(0).asType();

            put("map.normalized", inspector.normalizedType(map));
            put("set.normalized", inspector.normalizedType(set));
            put("enum.normalized", inspector.normalizedType(enumType));
            put("map.container", erased(inspector.containerClassificationType(map)));
            put("set.container", erased(inspector.containerClassificationType(set)));
            put("enum.container", erased(inspector.containerClassificationType(enumType)));
            put("wildcard.identity", inspector.exactJavaTypeIdentity(wildcard));
            put("owner.identity", inspector.exactJavaTypeIdentity(owned));
            put("model.normalized", inspector.normalizedType(modelVariable));
            put("method.normalized", inspector.normalizedType(methodVariable));
            put("model.variable", inspector.isModelTypeVariable(modelVariable));
            put("method.variable", inspector.isModelTypeVariable(methodVariable));
            put("raw.supported", inspector.isSupported(rawList));
            put("objectArray.supported", inspector.isSupported(objectArray));
            put("primitiveArray.supported", inspector.isSupported(primitiveArray));
            put("list.provides", inspector.providesCanonicalValue(list));
            put("list.accepts", inspector.acceptsDecodedValue(list));
            put("iterable.provides", inspector.providesCanonicalValue(iterable));
            put("iterable.accepts", inspector.acceptsDecodedValue(iterable));
            put("enum.provides", inspector.providesCanonicalValue(enumType));
            put("enum.accepts", inspector.acceptsDecodedValue(enumType));
            put("enum.canonical", inspector.canonicalDecodedTypeName(enumType));
            put("nested.deferred.compatible", inspector.areCompatibleNormalizedTypes(
                    "MAP<java.lang.String,LIST<DEFERRED_TYPE_VARIABLE>>",
                    "MAP<java.lang.String,LIST<java.lang.Integer>>"));
            put("container.kind.compatible", inspector.areCompatibleNormalizedTypes(
                    "SET<java.lang.String>", "LIST<java.lang.String>"));
            put("malformed.compatible", inspector.areCompatibleNormalizedTypes(
                    "LIST<java.lang.String", "LIST<java.lang.Integer>"));
            return false;
        }

        String value(String name) {
            return values.get(name);
        }

        private String erased(TypeMirror type) {
            return type == null ? null : processingEnv.getTypeUtils().erasure(type).toString();
        }

        private void put(String name, Object value) {
            values.put(name, value == null ? null : String.valueOf(value));
        }

        private static VariableElement field(TypeElement type, String name) {
            for (Element element : type.getEnclosedElements()) {
                if (element.getKind() == ElementKind.FIELD
                        && element.getSimpleName().contentEquals(name)) {
                    return (VariableElement) element;
                }
            }
            throw new AssertionError("Missing field " + type + "." + name);
        }

        private static ExecutableElement method(TypeElement type, String name) {
            for (Element element : type.getEnclosedElements()) {
                if (element.getKind() == ElementKind.METHOD
                        && element.getSimpleName().contentEquals(name)) {
                    return (ExecutableElement) element;
                }
            }
            throw new AssertionError("Missing method " + type + "." + name);
        }

        private static TypeElement nestedType(TypeElement type, String name) {
            for (Element element : type.getEnclosedElements()) {
                if (element instanceof TypeElement
                        && element.getSimpleName().contentEquals(name)) {
                    return (TypeElement) element;
                }
            }
            throw new AssertionError("Missing nested type " + type + "." + name);
        }
    }
}
