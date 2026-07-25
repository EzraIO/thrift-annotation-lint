package io.github.thriftannotationlint;

import org.junit.jupiter.api.Test;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static io.github.thriftannotationlint.CompilerTestSupport.compileWithAdditionalProcessor;
import static io.github.thriftannotationlint.CompilerTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RoundPlannerTest {
    @Test
    void ordersCurrentContainersBeforeReclassifiedHistoricalContainers() {
        OrderingProbe probe = new OrderingProbe();

        CompilerTestSupport.CompilationResult result = compileWithAdditionalProcessor(
                probe,
                source(
                        "example.AHistorical",
                        "package example;",
                        "import com.facebook.swift.codec.ThriftStruct;",
                        "@ThriftStruct",
                        "public class AHistorical extends GeneratedBase {}"));

        result.assertSucceeded();
        result.assertNoThriftAnnotationLintDiagnostics();
        assertEquals(
                Arrays.asList("example.ZCurrent", "example.AHistorical"),
                probe.secondRoundContainers);
    }

    private static final class OrderingProbe extends AbstractProcessor {
        private CompilationState state;
        private RoundPlanner planner;
        private int rounds;
        private List<String> secondRoundContainers = Collections.emptyList();

        @Override
        public synchronized void init(ProcessingEnvironment environment) {
            super.init(environment);
            state = new CompilationState(16);
            SwiftTypeInspector typeInspector = new SwiftTypeInspector(
                    environment.getTypeUtils(), environment.getElementUtils());
            SwiftModelClassifier modelClassifier = new SwiftModelClassifier();
            DemandClosure demandClosure = new DemandClosure(typeInspector, modelClassifier);
            planner = new RoundPlanner(
                    environment,
                    state,
                    typeInspector,
                    modelClassifier,
                    demandClosure);
        }

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
            if (roundEnvironment.processingOver()) {
                return false;
            }
            RoundPlanner.Plan plan = planner.plan(roundEnvironment);
            if (rounds++ == 0) {
                state.beginPendingAggregation();
                state.markPending("example.AHistorical", SwiftModel.Kind.STRUCT);
                generate(
                        "example.GeneratedBase",
                        "package example; public class GeneratedBase "
                                + "extends java.util.ArrayList<String> {}\n");
                generate(
                        "example.ZCurrent",
                        "package example;\n"
                                + "import com.facebook.swift.codec.ThriftStruct;\n"
                                + "@ThriftStruct public class ZCurrent "
                                + "extends java.util.ArrayList<String> {}\n");
            }
            else if (rounds == 2) {
                List<String> names = new ArrayList<String>();
                for (ContainerDemand demand : plan.containerDemands()) {
                    names.add(demand.element.getQualifiedName().toString());
                }
                secondRoundContainers = names;
            }
            return false;
        }

        private void generate(String className, String sourceText) {
            try {
                Writer writer = processingEnv.getFiler()
                        .createSourceFile(className)
                        .openWriter();
                try {
                    writer.write(sourceText);
                }
                finally {
                    writer.close();
                }
            }
            catch (IOException failure) {
                throw new IllegalStateException("Could not generate round-order fixture", failure);
            }
        }
    }
}
