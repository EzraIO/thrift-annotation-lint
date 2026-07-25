package io.github.thriftannotationlint;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.util.Collections;
import java.util.Set;

/** Standard JSR 269 entry point for ThriftAnnotationLint validation. */
@SupportedOptions({
        ThriftAnnotationLintProcessor.MODE_OPTION,
        ThriftAnnotationLintProcessor.MAX_EXACT_MODELS_OPTION
})
public final class ThriftAnnotationLintProcessor extends AbstractProcessor {
    public static final String MODE_OPTION = "thrift.annotation.lint.mode";
    public static final String MAX_EXACT_MODELS_OPTION = "thrift.annotation.lint.maxExactModels";

    private ValidationSession session;

    @Override
    public synchronized void init(ProcessingEnvironment environment) {
        super.init(environment);
        session = new ValidationSession();
        session.init(environment);
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
        return session.process(annotations, roundEnvironment);
    }
}
