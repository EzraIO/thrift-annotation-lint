package io.github.thriftannotationlint;

import org.junit.jupiter.api.Test;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static io.github.thriftannotationlint.CompilerTestSupport.CompilationResult;
import static io.github.thriftannotationlint.CompilerTestSupport.DependencyOutputMutator;
import static io.github.thriftannotationlint.CompilerTestSupport.Source;
import static io.github.thriftannotationlint.CompilerTestSupport.compileAgainstClasspath;
import static io.github.thriftannotationlint.CompilerTestSupport.compileAgainstClasspathWithAdditionalProcessor;
import static io.github.thriftannotationlint.CompilerTestSupport.compileAgainstClasspathWithMethodParametersOnly;
import static io.github.thriftannotationlint.CompilerTestSupport.compileAgainstClasspathWithoutDebug;
import static io.github.thriftannotationlint.CompilerTestSupport.compileAgainstMutatedClasspath;
import static io.github.thriftannotationlint.CompilerTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClasspathValidationTest {
    @Test
    void validatesEnumValueMetadataInheritedOnlyFromAClasspathInterface() {
        CompilationResult result = compileAgainstClasspath(
                new Source[]{source("dependency.InvalidEnumValueProvider",
                        "package dependency;",
                        "import com.facebook.swift.codec.ThriftEnumValue;",
                        "public interface InvalidEnumValueProvider {",
                        "  @ThriftEnumValue default long getValue() { return 1L; }",
                        "}")},
                source("current.InheritedEnumValue",
                        "package current;",
                        "public enum InheritedEnumValue",
                        "    implements dependency.InvalidEnumValueProvider { FIRST }"));

        result.assertFailedWith("AW6001");
    }

    @Test
    void reportsInvalidClasspathStructAtTheCurrentSourceReference() {
        CompilationResult result = compileAgainstClasspath(
                new Source[]{source("dependency.BadStruct",
                        "package dependency;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class BadStruct {",
                        "  @ThriftField(1) public Object value;",
                        "}")},
                holderSource("dependency.BadStruct"));

        result.assertFailedWith("AW4001");
        assertAtHolderReference(result.diagnostic("AW4001"));
    }

    @Test
    void substitutesParameterizedClasspathFieldTypes() {
        CompilationResult result = compileAgainstClasspath(
                new Source[]{source("dependency.Box",
                        "package dependency;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class Box<T> {",
                        "  @ThriftField(1) public T value;",
                        "}")},
                holderSource("dependency.Box<Object>"));

        result.assertFailedWith("AW4001");
        assertAtHolderReference(result.diagnostic("AW4001"));
    }

    @Test
    void permitsUnsupportedTypeArgumentsThatAreUnusedByThriftFields() {
        CompilationResult result = compileAgainstClasspath(
                new Source[]{source("dependency.Phantom",
                        "package dependency;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class Phantom<T> {",
                        "  @ThriftField(1) public String value;",
                        "}")},
                holderSource("dependency.Phantom<Object>"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void followsTransitiveClasspathStructReferences() {
        CompilationResult result = compileAgainstClasspath(
                new Source[]{
                        source("dependency.BadInner",
                                "package dependency;",
                                "import com.facebook.swift.codec.ThriftField;",
                                "import com.facebook.swift.codec.ThriftStruct;",
                                "@ThriftStruct",
                                "public class BadInner {",
                                "  @ThriftField(1) public Object value;",
                                "}"),
                        source("dependency.BadOuter",
                                "package dependency;",
                                "import com.facebook.swift.codec.ThriftField;",
                                "import com.facebook.swift.codec.ThriftStruct;",
                                "@ThriftStruct",
                                "public class BadOuter {",
                                "  @ThriftField(1) public BadInner inner;",
                                "}")},
                holderSource("dependency.BadOuter"));

        result.assertFailedWith("AW4001");
        assertAtHolderReference(result.diagnostic("AW4001"));
    }

    @Test
    void followsClasspathModelsReferencedOnlyByAnAnnotatedContainerRoot() {
        CompilationResult result = compileAgainstClasspath(
                new Source[]{source("dependency.BadContainerElement",
                        "package dependency;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class BadContainerElement {",
                        "  @ThriftField(1) public Object value;",
                        "}")},
                source("example.AnnotatedContainerDemandRoot",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "import java.util.ArrayList;",
                        "@ThriftStruct",
                        "public class AnnotatedContainerDemandRoot",
                        "    extends ArrayList<dependency.BadContainerElement> {}"));

        result.assertFailedWith("AW4001");
        Diagnostic<? extends JavaFileObject> diagnostic = result.diagnostic("AW4001");
        assertNotNull(diagnostic.getSource(), result.diagnosticSummary());
        assertTrue(
                diagnostic.getSource().getName().replace('\\', '/')
                        .endsWith("/example/AnnotatedContainerDemandRoot.java"),
                "Diagnostic must reference the annotated container root but was "
                        + diagnostic.getSource().getName());
        assertEquals(5L, diagnostic.getLineNumber(), result.diagnosticSummary());
    }

    @Test
    void rebuildsAContainerDemandClosureWhenADeepModelResolvesLater() {
        CompilationResult result = compileAgainstClasspathWithAdditionalProcessor(
                new GeneratedLeafProcessor(),
                new Source[]{
                        source("dependency.MiddleModel",
                                "package dependency;",
                                "import com.facebook.swift.codec.ThriftField;",
                                "import com.facebook.swift.codec.ThriftStruct;",
                                "@ThriftStruct",
                                "public class MiddleModel<T> {",
                                "  @ThriftField(1) public T leaf;",
                                "}"),
                        source("dependency.OuterModel",
                                "package dependency;",
                                "import com.facebook.swift.codec.ThriftField;",
                                "import com.facebook.swift.codec.ThriftStruct;",
                                "@ThriftStruct",
                                "public class OuterModel<T> {",
                                "  @ThriftField(1) public MiddleModel<T> middle;",
                                "}")},
                source("example.GeneratedContainerClosure",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "import java.util.ArrayList;",
                        "@ThriftStruct",
                        "public class GeneratedContainerClosure",
                        "    extends ArrayList<dependency.OuterModel<dependency.GeneratedLeaf>> {}"));

        result.assertFailedWith("AW4001");
        Diagnostic<? extends JavaFileObject> diagnostic = result.diagnostic("AW4001");
        assertNotNull(diagnostic.getSource(), result.diagnosticSummary());
        assertTrue(
                diagnostic.getSource().getName().replace('\\', '/')
                        .endsWith("/example/GeneratedContainerClosure.java"),
                result.diagnosticSummary());
    }

    @Test
    void doesNotTreatClasspathArgNamesAsAuthoritative() {
        CompilationResult result = compileAgainstClasspath(
                new Source[]{source("dependency.BuiltValue",
                        "package dependency;",
                        "import com.facebook.swift.codec.ThriftConstructor;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class BuiltValue {",
                        "  private final String value;",
                        "  @ThriftConstructor",
                        "  public BuiltValue(@ThriftField(1) String value) { this.value = value; }",
                        "  @ThriftField(1) public String getValue() { return value; }",
                        "}")},
                holderSource("dependency.BuiltValue"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void stillComparesTypesMergedByExplicitClasspathParameterIds() {
        CompilationResult result = compileAgainstClasspath(
                new Source[]{source("dependency.ConflictingBuiltValue",
                        "package dependency;",
                        "import com.facebook.swift.codec.ThriftConstructor;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class ConflictingBuiltValue {",
                        "  @ThriftConstructor",
                        "  public ConflictingBuiltValue(@ThriftField(1) Integer value) {}",
                        "  @ThriftField(1) public String getValue() { return \"\"; }",
                        "}")},
                holderSource("dependency.ConflictingBuiltValue"));

        result.assertFailedWith("AW4002");
        assertAtHolderReference(result.diagnostic("AW4002"));
    }

    @Test
    void unreliableClasspathParametersDoNotHideIndependentReliableErrors() {
        CompilationResult result = compileAgainstClasspath(
                new Source[]{source("dependency.MixedClasspathModel",
                        "package dependency;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class MixedClasspathModel {",
                        "  @ThriftField(1) public String first;",
                        "  @ThriftField(1) public String second;",
                        "  @ThriftField public void inject(@ThriftField String unknown) {}",
                        "}")},
                holderSource("dependency.MixedClasspathModel"));

        result.assertFailedWith("AW2002");
        assertAtHolderReference(result.diagnostic("AW2002"));
    }

    @Test
    void usesLocalVariableTableNamesForClasspathIdInference() {
        CompilationResult result = compileAgainstClasspath(
                new Source[]{source("dependency.LvtConflict",
                        "package dependency;",
                        "import com.facebook.swift.codec.ThriftConstructor;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class LvtConflict {",
                        "  @ThriftConstructor",
                        "  public LvtConflict(@ThriftField Integer value) {}",
                        "  @ThriftField(1) public String getValue() { return \"\"; }",
                        "}")},
                holderSource("dependency.LvtConflict"));

        result.assertFailedWith("AW4002");
        assertAtHolderReference(result.diagnostic("AW4002"));
    }

    @Test
    void doesNotTrustMethodParametersThatSwiftParanamerCannotRead() {
        CompilationResult result = compileAgainstClasspathWithMethodParametersOnly(
                new Source[]{source("dependency.ParametersOnly",
                        "package dependency;",
                        "import com.facebook.swift.codec.ThriftConstructor;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class ParametersOnly {",
                        "  @ThriftConstructor",
                        "  public ParametersOnly(@ThriftField String value) {}",
                        "  @ThriftField(1) public String getValue() { return \"\"; }",
                        "}")},
                holderSource("dependency.ParametersOnly"));

        result.assertFailedWith("AW3003");
        assertAtHolderReference(result.diagnostic("AW3003"));
    }

    @Test
    void readsLvtSlotsAfterWidePrimitiveArraysCorrectly() {
        CompilationResult result = compileAgainstClasspath(
                new Source[]{source("dependency.ArraySlotModel",
                        "package dependency;",
                        "import com.facebook.swift.codec.ThriftConstructor;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class ArraySlotModel {",
                        "  @ThriftConstructor",
                        "  public ArraySlotModel(@ThriftField(1) long[] values,",
                        "      @ThriftField Integer count) {}",
                        "  @ThriftField(1) public long[] getValues() { return null; }",
                        "  @ThriftField(2) public String getCount() { return \"\"; }",
                        "}")},
                holderSource("dependency.ArraySlotModel"));

        result.assertFailedWith("AW4002");
        assertAtHolderReference(result.diagnostic("AW4002"));
    }

    @Test
    void acceptsNonZeroLvtStartsLikeOfficialParanamer() {
        CompilationResult result = compileAgainstMutatedClasspath(
                mutateLvtEntry(
                        "dependency/NonZeroStart.class",
                        "value",
                        new LvtEntryMutation() {
                            @Override
                            public void mutate(byte[] classFile, int entryOffset) {
                                int length = u2(classFile, entryOffset + 2);
                                putU2(classFile, entryOffset, 1);
                                putU2(classFile, entryOffset + 2, length - 1);
                            }
                        }),
                new Source[]{source("dependency.NonZeroStart",
                        "package dependency;",
                        "import com.facebook.swift.codec.ThriftConstructor;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class NonZeroStart {",
                        "  @ThriftConstructor",
                        "  public NonZeroStart(@ThriftField Integer value) {}",
                        "  @ThriftField(1) public String getValue() { return \"\"; }",
                        "}")},
                holderSource("dependency.NonZeroStart"));

        result.assertFailedWith("AW4002");
        assertAtHolderReference(result.diagnostic("AW4002"));
    }

    @Test
    void rejectsPartialLvtThatWouldCrashOfficialParanamerConsumers() {
        CompilationResult result = compileAgainstMutatedClasspath(
                mutateLvtEntry(
                        "dependency/PartialLvt.class",
                        "right",
                        new LvtEntryMutation() {
                            @Override
                            public void mutate(byte[] classFile, int entryOffset) {
                                // Slot zero belongs to "this" and is outside Paranamer's
                                // instance-parameter range, leaving a one-name partial result.
                                putU2(classFile, entryOffset + 8, 0);
                            }
                        }),
                new Source[]{source("dependency.PartialLvt",
                        "package dependency;",
                        "import com.facebook.swift.codec.ThriftConstructor;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class PartialLvt {",
                        "  @ThriftConstructor",
                        "  public PartialLvt(",
                        "      @ThriftField(1) String left,",
                        "      @ThriftField(2) String right) {}",
                        "  @ThriftField(1) public String getLeft() { return \"\"; }",
                        "  @ThriftField(2) public String getRight() { return \"\"; }",
                        "}")},
                holderSource("dependency.PartialLvt"));

        result.assertFailedWith("AW3003");
        assertAtHolderReference(result.diagnostic("AW3003"));
    }

    @Test
    void unknownExplicitIdParameterDoesNotCollapseReliableDuplicateFields() {
        CompilationResult result = compileAgainstClasspathWithoutDebug(
                new Source[]{source("dependency.AmbiguousIdModel",
                        "package dependency;",
                        "import com.facebook.swift.codec.ThriftConstructor;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class AmbiguousIdModel {",
                        "  @ThriftField(1) public String first;",
                        "  @ThriftField(1) public String second;",
                        "  @ThriftConstructor",
                        "  public AmbiguousIdModel(@ThriftField(1) String value) {}",
                        "}")},
                holderSource("dependency.AmbiguousIdModel"));

        result.assertFailedWith("AW2002");
        assertAtHolderReference(result.diagnostic("AW2002"));
    }

    @Test
    void usesGeneralParanamerArgNamesWhenClasspathLvtIsAbsent() {
        CompilationResult result = compileAgainstClasspathWithoutDebug(
                new Source[]{source("dependency.ArgNameConflict",
                        "package dependency;",
                        "import com.facebook.swift.codec.ThriftConstructor;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class ArgNameConflict {",
                        "  @ThriftField(2) public String arg0;",
                        "  @ThriftField(1) public String value;",
                        "  @ThriftConstructor",
                        "  public ArgNameConflict(@ThriftField(1) String sourceName) {}",
                        "}")},
                holderSource("dependency.ArgNameConflict"));

        result.assertFailedWith("AW2003");
        assertAtHolderReference(result.diagnostic("AW2003"));
    }

    @Test
    void resolvesInheritedParameterNamesFromTheDeclaringClasspathType() {
        CompilationResult result = compileAgainstClasspath(
                new Source[]{source("dependency.InjectionBase",
                        "package dependency;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "public class InjectionBase {",
                        "  @ThriftField public void inject(",
                        "      @ThriftField(1) String left,",
                        "      @ThriftField(2) String right) {}",
                        "}")},
                source("current.InheritedModel",
                        "package current;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class InheritedModel extends dependency.InjectionBase {",
                        "  @ThriftField(1) public String arg0;",
                        "  @ThriftField(2) public String arg1;",
                        "}"));

        result.assertFailedWith("AW2002");
    }

    @Test
    void validatesReachableClasspathEnumsBeforeContainerInterfaces() {
        CompilationResult result = compileAgainstClasspath(
                new Source[]{source("dependency.IterableBadEnum",
                        "package dependency;",
                        "import com.facebook.swift.codec.ThriftEnumValue;",
                        "import java.util.Collections;",
                        "import java.util.Iterator;",
                        "public enum IterableBadEnum implements Iterable<String> {",
                        "  FIRST;",
                        "  @ThriftEnumValue public long getValue() { return 1L; }",
                        "  public Iterator<String> iterator() {",
                        "    return Collections.<String>emptyList().iterator();",
                        "  }",
                        "}")},
                holderSource("dependency.IterableBadEnum"));

        result.assertFailedWith("AW6001");
        assertFalse(result.hasCode("AW4002"), result.diagnosticSummary());
        assertAtHolderReference(result.diagnostic("AW6001"));
    }

    @Test
    void treatsReachableClasspathEnumsAsEnumsBeforeContainerInterfaces() {
        CompilationResult result = compileAgainstClasspath(
                new Source[]{source("dependency.IterableEnum",
                        "package dependency;",
                        "import java.util.Collections;",
                        "import java.util.Iterator;",
                        "public enum IterableEnum implements Iterable<String> {",
                        "  FIRST;",
                        "  public Iterator<String> iterator() {",
                        "    return Collections.<String>emptyList().iterator();",
                        "  }",
                        "}")},
                holderSource("dependency.IterableEnum"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
    }

    @Test
    void relocatesGenericClasspathCyclesToTheExactUseSite() {
        CompilationResult result = compileAgainstClasspath(
                new Source[]{
                        source("dependency.GenericLink",
                                "package dependency;",
                                "import com.facebook.swift.codec.ThriftField;",
                                "import com.facebook.swift.codec.ThriftStruct;",
                                "@ThriftStruct",
                                "public class GenericLink<T> {",
                                "  @ThriftField(1) public T value;",
                                "}"),
                        source("dependency.CyclicNode",
                                "package dependency;",
                                "import com.facebook.swift.codec.ThriftField;",
                                "import com.facebook.swift.codec.ThriftStruct;",
                                "@ThriftStruct",
                                "public class CyclicNode {",
                                "  @ThriftField(1) public GenericLink<CyclicNode> back;",
                                "}")},
                source("current.ExactCycleHolder",
                        "package current;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class ExactCycleHolder {",
                        "  @ThriftField(1) public dependency.GenericLink<String> alphaSafe;",
                        "  @ThriftField(2) public dependency.GenericLink<dependency.CyclicNode> zetaCyclic;",
                        "}"));

        result.assertFailedWith("AW4003");
        assertNotNull(result.diagnostic("AW4003").getSource());
        assertTrue(
                result.diagnostic("AW4003").getSource().getName().replace('\\', '/')
                        .endsWith("/current/ExactCycleHolder.java"));
        assertEquals(7, result.diagnostic("AW4003").getLineNumber());
    }

    private static DependencyOutputMutator mutateLvtEntry(
            final String classResource,
            final String localName,
            final LvtEntryMutation mutation) {
        return new DependencyOutputMutator() {
            @Override
            public void mutate(Path dependencyOutput) throws IOException {
                Path classFile = dependencyOutput.resolve(classResource);
                byte[] bytes = Files.readAllBytes(classFile);
                ConstantPool constantPool = readConstantPool(bytes);
                int offset = constantPool.endOffset + 6;
                int interfaceCount = u2(bytes, offset);
                offset += 2 + interfaceCount * 2;
                offset = skipMembers(bytes, offset);

                int methodCount = u2(bytes, offset);
                offset += 2;
                boolean mutated = false;
                for (int method = 0; method < methodCount; method++) {
                    offset += 6;
                    int attributeCount = u2(bytes, offset);
                    offset += 2;
                    for (int attribute = 0; attribute < attributeCount; attribute++) {
                        String attributeName = constantPool.utf8.get(u2(bytes, offset));
                        int length = u4(bytes, offset + 2);
                        int body = offset + 6;
                        if ("Code".equals(attributeName)) {
                            mutated |= mutateCodeLvt(
                                    bytes,
                                    body,
                                    constantPool.utf8,
                                    localName,
                                    mutation);
                        }
                        offset = body + length;
                    }
                }
                if (!mutated) {
                    throw new IOException(
                            "LocalVariableTable entry '" + localName
                                    + "' was not found in " + classResource);
                }
                Files.write(classFile, bytes);
            }
        };
    }

    private static boolean mutateCodeLvt(
            byte[] bytes,
            int codeAttribute,
            Map<Integer, String> utf8,
            String localName,
            LvtEntryMutation mutation) throws IOException {
        int codeLength = u4(bytes, codeAttribute + 4);
        int offset = codeAttribute + 8 + codeLength;
        int exceptionCount = u2(bytes, offset);
        offset += 2 + exceptionCount * 8;
        int attributeCount = u2(bytes, offset);
        offset += 2;
        boolean mutated = false;
        for (int attribute = 0; attribute < attributeCount; attribute++) {
            String name = utf8.get(u2(bytes, offset));
            int length = u4(bytes, offset + 2);
            int body = offset + 6;
            if ("LocalVariableTable".equals(name)) {
                int entryCount = u2(bytes, body);
                int entry = body + 2;
                for (int index = 0; index < entryCount; index++, entry += 10) {
                    String entryName = utf8.get(u2(bytes, entry + 4));
                    if (localName.equals(entryName)) {
                        mutation.mutate(bytes, entry);
                        mutated = true;
                    }
                }
            }
            offset = body + length;
        }
        return mutated;
    }

    private static int skipMembers(byte[] bytes, int offset) throws IOException {
        int memberCount = u2(bytes, offset);
        offset += 2;
        for (int member = 0; member < memberCount; member++) {
            offset += 6;
            int attributeCount = u2(bytes, offset);
            offset += 2;
            for (int attribute = 0; attribute < attributeCount; attribute++) {
                int length = u4(bytes, offset + 2);
                offset += 6 + length;
                requireOffset(bytes, offset);
            }
        }
        return offset;
    }

    private static ConstantPool readConstantPool(byte[] bytes) throws IOException {
        if (bytes.length < 10
                || (bytes[0] & 0xFF) != 0xCA
                || (bytes[1] & 0xFF) != 0xFE
                || (bytes[2] & 0xFF) != 0xBA
                || (bytes[3] & 0xFF) != 0xBE) {
            throw new IOException("Invalid class fixture");
        }
        int count = u2(bytes, 8);
        int offset = 10;
        Map<Integer, String> utf8 = new HashMap<Integer, String>();
        for (int index = 1; index < count; index++) {
            int tag = bytes[offset++] & 0xFF;
            if (tag == 1) {
                int length = u2(bytes, offset);
                utf8.put(index, new String(
                        bytes, offset + 2, length, StandardCharsets.UTF_8));
                offset += 2 + length;
            }
            else if (tag == 3 || tag == 4) {
                offset += 4;
            }
            else if (tag == 5 || tag == 6) {
                offset += 8;
                index++;
            }
            else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                offset += 2;
            }
            else if (tag == 9 || tag == 10 || tag == 11 || tag == 12
                    || tag == 17 || tag == 18) {
                offset += 4;
            }
            else if (tag == 15) {
                offset += 3;
            }
            else {
                throw new IOException("Unsupported class fixture constant-pool tag " + tag);
            }
            requireOffset(bytes, offset);
        }
        return new ConstantPool(utf8, offset);
    }

    private static int u2(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) << 8 | bytes[offset + 1] & 0xFF;
    }

    private static int u4(byte[] bytes, int offset) throws IOException {
        long value = (long) (bytes[offset] & 0xFF) << 24
                | (long) (bytes[offset + 1] & 0xFF) << 16
                | (long) (bytes[offset + 2] & 0xFF) << 8
                | bytes[offset + 3] & 0xFFL;
        if (value > Integer.MAX_VALUE) {
            throw new IOException("Oversized class fixture structure");
        }
        return (int) value;
    }

    private static void putU2(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 8);
        bytes[offset + 1] = (byte) value;
    }

    private static void requireOffset(byte[] bytes, int offset) throws IOException {
        if (offset < 0 || offset > bytes.length) {
            throw new IOException("Malformed class fixture");
        }
    }

    private interface LvtEntryMutation {
        void mutate(byte[] classFile, int entryOffset);
    }

    private static final class GeneratedLeafProcessor extends AbstractProcessor {
        private boolean generated;

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
            if (generated || roundEnvironment.processingOver()) {
                return false;
            }
            generated = true;
            try {
                Writer writer = processingEnv.getFiler()
                        .createSourceFile("dependency.GeneratedLeaf")
                        .openWriter();
                try {
                    writer.write("package dependency;\n"
                            + "public class GeneratedLeaf {}\n");
                }
                finally {
                    writer.close();
                }
            }
            catch (IOException failure) {
                throw new IllegalStateException(
                        "Could not generate the deep container fixture", failure);
            }
            return false;
        }
    }

    private static final class ConstantPool {
        private final Map<Integer, String> utf8;
        private final int endOffset;

        private ConstantPool(Map<Integer, String> utf8, int endOffset) {
            this.utf8 = utf8;
            this.endOffset = endOffset;
        }
    }

    private static Source holderSource(String fieldType) {
        return source("current.Holder",
                "package current;",
                "import com.facebook.swift.codec.ThriftField;",
                "import com.facebook.swift.codec.ThriftStruct;",
                "@ThriftStruct",
                "public class Holder {",
                "  @ThriftField(1) public " + fieldType + " value;",
                "}");
    }

    private static void assertAtHolderReference(
            Diagnostic<? extends JavaFileObject> diagnostic) {
        assertNotNull(diagnostic.getSource(), "Diagnostic must reference the current source");
        assertTrue(
                diagnostic.getSource().getName().replace('\\', '/')
                        .endsWith("/current/Holder.java"),
                "Diagnostic must reference Holder.java but was " + diagnostic.getSource().getName());
        assertEquals(6, diagnostic.getLineNumber(),
                "Diagnostic must point to the Holder field reference");
    }
}
