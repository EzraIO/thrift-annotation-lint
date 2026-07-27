package io.github.thriftannotationlint.internal.extract;

import io.github.thriftannotationlint.CompilerTestSupport;
import io.github.thriftannotationlint.internal.planning.CompilationState;
import io.github.thriftannotationlint.internal.planning.DemandClosure;
import io.github.thriftannotationlint.internal.planning.ModelDemand;
import io.github.thriftannotationlint.internal.planning.RoundPlanner;
import io.github.thriftannotationlint.internal.types.IncompleteTypeGate;
import io.github.thriftannotationlint.internal.types.ThriftTypeInspector;

import org.junit.jupiter.api.Test;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Types;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Set;

import static io.github.thriftannotationlint.CompilerTestSupport.compileWithAdditionalProcessor;
import static io.github.thriftannotationlint.CompilerTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class SwiftMemberResolverOwnerTest {
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
        assertEquals(2, probe.memberEnumerations);
    }

    @Test
    void plannerAndExtractorShareOneRoundMemberEnumeration() {
        SharedRoundCacheProbe probe = new SharedRoundCacheProbe();

        CompilerTestSupport.CompilationResult result = compileWithAdditionalProcessor(
                probe,
                source("example.EnumValue",
                        "package example;",
                        "public interface EnumValue {",
                        "  @com.facebook.swift.codec.ThriftEnumValue",
                        "  default int value() { return 1; }",
                        "}"),
                source("example.InheritedEnumFixture",
                        "package example;",
                        "public enum InheritedEnumFixture implements EnumValue { READY }"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
        assertEquals(1, probe.memberEnumerations);
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

    private static final class ExecutableOwnerProbe extends ProbeProcessor {
        private boolean inspected;
        private boolean methodVariableRestored;
        private int memberEnumerations;

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

            MemberResolutionMetrics metrics = new MemberResolutionMetrics();
            SwiftMemberResolver cachedResolver = new SwiftMemberResolver(
                    processingEnv.getElementUtils(), processingEnv.getTypeUtils(), metrics);
            cachedResolver.beginRound();
            cachedResolver.effectiveMethods(fixture, "example.Missing", false);
            cachedResolver.effectiveMethods(fixture, "example.Missing", false);
            cachedResolver.hierarchy(fixture);
            cachedResolver.hierarchy(fixture);
            cachedResolver.beginRound();
            cachedResolver.effectiveMethods(fixture, "example.Missing", false);
            memberEnumerations = metrics.memberEnumerations();

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

    private static final class SharedRoundCacheProbe extends ProbeProcessor {
        private boolean inspected;
        private int memberEnumerations;

        @Override
        public boolean process(
                Set<? extends TypeElement> annotations,
                RoundEnvironment roundEnvironment) {
            if (inspected || roundEnvironment.processingOver()) {
                return false;
            }
            TypeElement fixture = processingEnv.getElementUtils()
                    .getTypeElement("example.InheritedEnumFixture");
            if (fixture == null) {
                return false;
            }
            inspected = true;

            MemberResolutionMetrics metrics = new MemberResolutionMetrics();
            SwiftMemberResolver memberResolver = new SwiftMemberResolver(
                    processingEnv.getElementUtils(), processingEnv.getTypeUtils(), metrics);
            ThriftTypeInspector typeInspector = new ThriftTypeInspector(
                    processingEnv.getTypeUtils(), processingEnv.getElementUtils());
            CompilationState state = new CompilationState(16);
            SwiftModelClassifier modelClassifier = new SwiftModelClassifier();
            DemandClosure demandClosure = new DemandClosure(typeInspector, modelClassifier);
            RoundPlanner planner = new RoundPlanner(
                    processingEnv,
                    state,
                    typeInspector,
                    modelClassifier,
                    demandClosure,
                    memberResolver);
            SwiftModelExtractor extractor = new SwiftModelExtractor(
                    processingEnv,
                    typeInspector,
                    new IncompleteTypeGate(),
                    memberResolver);

            extractor.beginRound();
            RoundPlanner.Plan plan = planner.plan(roundEnvironment);
            for (ModelDemand demand : plan.modelDemands()) {
                if (demand.type().equals(fixture)) {
                    extractor.extract(
                            demand.declaredType(),
                            demand.kind(),
                            demand.identity(),
                            demand.cacheKey(),
                            demand.dialect(),
                            state.compilationTypes());
                }
            }
            memberEnumerations = metrics.memberEnumerations();
            return false;
        }
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
                SwiftMemberResolverOwnerTest.class.getClassLoader(),
                new Class<?>[]{type},
                handler));
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
