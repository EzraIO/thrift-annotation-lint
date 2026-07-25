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
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Set;

import static io.github.thriftannotationlint.CompilerTestSupport.compileWithAdditionalProcessor;
import static io.github.thriftannotationlint.CompilerTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TypeSubsystemOwnerTest {
    @Test
    void detectsAnErrorTypeNestedOnlyInAParameterizedOwner() {
        OwnerErrorProbe probe = new OwnerErrorProbe();

        CompilerTestSupport.CompilationResult result = compileWithAdditionalProcessor(
                probe,
                source("example.OwnerErrorFixture",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftField;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class OwnerErrorFixture {",
                        "  @ThriftField(1)",
                        "  public Box<Outer<LaterGenerated>.Inner> value;",
                        "  public static class Outer<T> { public class Inner {} }",
                        "  @ThriftStruct public static class Box<T> {",
                        "    @ThriftField(1) public String marker;",
                        "  }",
                        "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
        assertTrue(probe.unresolvedOwnerDetected);
    }

    @Test
    void elementFallbackSubstitutesParameterizedOwnerArguments() {
        HierarchyOwnerProbe probe = new HierarchyOwnerProbe();

        CompilerTestSupport.CompilationResult result = compileWithAdditionalProcessor(
                probe,
                source("example.FallbackFixture",
                        "package example;",
                        "public class FallbackFixture {",
                        "  public Owner<Integer>.Inner value;",
                        "  public Owner.Inner rawValue;",
                        "  public static class Owner<T> {",
                        "    public class Inner extends java.util.HashMap<T, String> {}",
                        "  }",
                        "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
        assertEquals("java.lang.Integer", probe.mapKeyType);
        assertEquals(0, probe.rawMapArguments);
        assertTrue(probe.selfReferentialOwnerTerminated);
    }

    @Test
    void restoresExecutableTypeVariablesInsideAnOwnerWithNoLocalArguments() {
        ExecutableOwnerProbe probe = new ExecutableOwnerProbe();

        CompilerTestSupport.CompilationResult result = compileWithAdditionalProcessor(
                probe,
                source("example.MemberFixture",
                        "package example;",
                        "public class MemberFixture<X> {",
                        "  public <M extends CharSequence> Owner<M>.Inner value() { return null; }",
                        "  public static class Owner<T> { public class Inner {} }",
                        "}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
        assertTrue(probe.methodVariableRestored);
    }

    private abstract static class ProbeProcessor extends AbstractProcessor {
        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Collections.singleton("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }
    }

    private static final class OwnerErrorProbe extends ProbeProcessor {
        private boolean inspected;
        private boolean generated;
        private boolean unresolvedOwnerDetected;

        @Override
        public boolean process(
                Set<? extends TypeElement> annotations,
                RoundEnvironment roundEnvironment) {
            if (!inspected && !roundEnvironment.processingOver()) {
                TypeElement fixture = processingEnv.getElementUtils()
                        .getTypeElement("example.OwnerErrorFixture");
                if (fixture != null) {
                    inspected = true;
                    unresolvedOwnerDetected = new UnresolvedSymbolInspector(
                            processingEnv.getElementUtils(),
                            processingEnv.getTypeUtils()).hasUnresolvedSymbols(
                            fixture,
                            (DeclaredType) fixture.asType(),
                            SwiftModel.Kind.STRUCT);
                }
            }
            if (!generated && !roundEnvironment.processingOver()) {
                generated = true;
                generateLaterType();
            }
            return false;
        }

        private void generateLaterType() {
            try {
                JavaFileObject source = processingEnv.getFiler()
                        .createSourceFile("example.LaterGenerated");
                Writer writer = source.openWriter();
                try {
                    writer.write("package example; public class LaterGenerated {}\n");
                }
                finally {
                    writer.close();
                }
            }
            catch (IOException failure) {
                throw new IllegalStateException("Could not generate owner argument fixture", failure);
            }
        }
    }

    private static final class HierarchyOwnerProbe extends ProbeProcessor {
        private boolean inspected;
        private String mapKeyType;
        private int rawMapArguments = -1;
        private boolean selfReferentialOwnerTerminated;

        @Override
        public boolean process(
                Set<? extends TypeElement> annotations,
                RoundEnvironment roundEnvironment) {
            if (inspected || roundEnvironment.processingOver()) {
                return false;
            }
            TypeElement fixture = processingEnv.getElementUtils()
                    .getTypeElement("example.FallbackFixture");
            if (fixture == null) {
                return false;
            }
            inspected = true;
            DeclaredType candidate = (DeclaredType) field(fixture, "value").asType();
            Types fallbackOnly = rejectDirectSupertypes(processingEnv.getTypeUtils());
            DeclaredType map = new TypeHierarchyResolver(fallbackOnly).asSupertype(
                    candidate,
                    processingEnv.getElementUtils().getTypeElement("java.util.Map"));
            if (map != null && !map.getTypeArguments().isEmpty()) {
                mapKeyType = map.getTypeArguments().get(0).toString();
            }
            DeclaredType rawCandidate = (DeclaredType) field(fixture, "rawValue").asType();
            DeclaredType rawMap = new TypeHierarchyResolver(fallbackOnly).asSupertype(
                    rawCandidate,
                    processingEnv.getElementUtils().getTypeElement("java.util.Map"));
            if (rawMap != null) {
                rawMapArguments = rawMap.getTypeArguments().size();
            }
            DeclaredType cyclicOwner = selfEnclosing(candidate);
            selfReferentialOwnerTerminated = new TypeHierarchyResolver(fallbackOnly)
                    .asSupertype(
                            cyclicOwner,
                            processingEnv.getElementUtils().getTypeElement("java.util.Map"))
                    != null;
            return false;
        }
    }

    private static final class ExecutableOwnerProbe extends ProbeProcessor {
        private boolean inspected;
        private boolean methodVariableRestored;

        @Override
        public boolean process(
                Set<? extends TypeElement> annotations,
                RoundEnvironment roundEnvironment) {
            if (inspected || roundEnvironment.processingOver()) {
                return false;
            }
            TypeElement fixture = processingEnv.getElementUtils()
                    .getTypeElement("example.MemberFixture");
            if (fixture == null) {
                return false;
            }
            inspected = true;

            ExecutableElement method = method(fixture, "value");
            TypeElement owner = nestedType(fixture, "Owner");
            TypeElement inner = nestedType(owner, "Inner");
            Types delegate = processingEnv.getTypeUtils();
            DeclaredType wrongOwner = delegate.getDeclaredType(
                    owner,
                    processingEnv.getElementUtils().getTypeElement("java.lang.String").asType());
            final DeclaredType wrongReturn = delegate.getDeclaredType(wrongOwner, inner);
            final ExecutableType declaredExecutable = (ExecutableType) method.asType();
            ExecutableType wrongExecutable = proxy(
                    ExecutableType.class,
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method invoked, Object[] arguments)
                                throws Throwable {
                            if ("getReturnType".equals(invoked.getName())) {
                                return wrongReturn;
                            }
                            return invokeDelegate(declaredExecutable, invoked, arguments);
                        }
                    });
            Types memberTypes = overrideAsMemberOf(delegate, wrongExecutable);
            SwiftMemberResolver.ResolvedExecutable resolved = new SwiftMemberResolver(
                    processingEnv.getElementUtils(), memberTypes).resolveExecutable(
                    (DeclaredType) fixture.asType(), method);

            DeclaredType restored = (DeclaredType) resolved.returnType();
            DeclaredType restoredOwner = (DeclaredType) restored.getEnclosingType();
            TypeMirror argument = restoredOwner.getTypeArguments().get(0);
            methodVariableRestored = argument.getKind() == javax.lang.model.type.TypeKind.TYPEVAR
                    && ((TypeVariable) argument).asElement().equals(
                    method.getTypeParameters().get(0));
            return false;
        }
    }

    private static Types rejectDirectSupertypes(final Types delegate) {
        return proxy(Types.class, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments)
                    throws Throwable {
                if ("directSupertypes".equals(method.getName())) {
                    throw new IllegalStateException("force element fallback");
                }
                return invokeDelegate(delegate, method, arguments);
            }
        });
    }

    private static Types overrideAsMemberOf(
            final Types delegate,
            final ExecutableType executable) {
        return proxy(Types.class, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments)
                    throws Throwable {
                if ("asMemberOf".equals(method.getName())) {
                    return executable;
                }
                return invokeDelegate(delegate, method, arguments);
            }
        });
    }

    private static DeclaredType selfEnclosing(final DeclaredType delegate) {
        final DeclaredType[] cyclic = new DeclaredType[1];
        cyclic[0] = proxy(DeclaredType.class, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments)
                    throws Throwable {
                if ("getEnclosingType".equals(method.getName())) {
                    return cyclic[0];
                }
                return invokeDelegate(delegate, method, arguments);
            }
        });
        return cyclic[0];
    }

    private static Object invokeDelegate(
            Object delegate,
            Method method,
            Object[] arguments) throws Throwable {
        try {
            return method.invoke(delegate, arguments);
        }
        catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(
                TypeSubsystemOwnerTest.class.getClassLoader(),
                new Class<?>[]{type},
                handler));
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
