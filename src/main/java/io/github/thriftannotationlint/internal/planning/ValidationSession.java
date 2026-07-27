package io.github.thriftannotationlint.internal.planning;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.TypeElement;
import java.util.Set;

/** Thin lifecycle facade used by the public annotation processor entry point. */
public final class ValidationSession {
    private final RoundValidationEngine engine = new RoundValidationEngine();

    public synchronized void init(ProcessingEnvironment environment) {
        engine.init(environment);
    }

    public boolean process(
            Set<? extends TypeElement> annotations,
            RoundEnvironment roundEnvironment) {
        return engine.process(annotations, roundEnvironment);
    }
}
