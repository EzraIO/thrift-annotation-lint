package io.github.thriftannotationlint.internal.planning;

import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.extract.SwiftMemberResolver;
import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;
import io.github.thriftannotationlint.internal.model.ThriftAnnotations;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import java.util.List;
import java.util.Map;

/** Discovers enum candidates from value annotations and inherited annotated methods. */
final class RoundEnumCandidateCollector {
    interface CandidateRegistrar {
        void add(
                TypeElement type,
                SwiftModel.Kind kind,
                ThriftAnnotationDialect dialect,
                Map<String, ModelDemand> candidates);
    }

    private final ProcessingEnvironment environment;
    private final SwiftMemberResolver memberResolver;
    private final CandidateRegistrar registrar;

    RoundEnumCandidateCollector(
            ProcessingEnvironment environment,
            SwiftMemberResolver memberResolver,
            CandidateRegistrar registrar) {
        this.environment = environment;
        this.memberResolver = memberResolver;
        this.registrar = registrar;
    }

    void collect(
            RoundEnvironment roundEnvironment,
            Map<String, ModelDemand> candidates,
            List<Finding> findings) {
        for (ThriftAnnotationDialect dialect : ThriftAnnotationDialect.values()) {
            addUnknownValueOwners(roundEnvironment, dialect, candidates);
            addValueOwners(roundEnvironment, dialect, candidates, findings);
        }
        for (Element root : roundEnvironment.getRootElements()) {
            addInheritedValueOwners(root, candidates);
        }
    }

    private void addUnknownValueOwners(
            RoundEnvironment roundEnvironment,
            ThriftAnnotationDialect dialect,
            Map<String, ModelDemand> candidates) {
        String annotationName = dialect.thriftEnumUnknownValue();
        TypeElement annotation = annotationName == null ? null : element(annotationName);
        if (annotation == null) {
            return;
        }
        for (Element annotated : roundEnvironment.getElementsAnnotatedWith(annotation)) {
            Element owner = annotated.getEnclosingElement();
            if (owner instanceof TypeElement && owner.getKind() == ElementKind.ENUM) {
                registrar.add((TypeElement) owner, SwiftModel.Kind.ENUM, dialect, candidates);
            }
        }
    }

    private void addValueOwners(
            RoundEnvironment roundEnvironment,
            ThriftAnnotationDialect dialect,
            Map<String, ModelDemand> candidates,
            List<Finding> findings) {
        TypeElement annotation = element(dialect.thriftEnumValue());
        if (annotation == null) {
            return;
        }
        for (Element annotated : roundEnvironment.getElementsAnnotatedWith(annotation)) {
            Element owner = annotated.getEnclosingElement();
            if (owner instanceof TypeElement && owner.getKind() == ElementKind.ENUM) {
                registrar.add((TypeElement) owner, SwiftModel.Kind.ENUM, dialect, candidates);
            }
            else if (owner instanceof TypeElement && !owner.getKind().isInterface()) {
                findings.add(Finding.error(
                        DiagnosticCode.INVALID_ENUM_VALUE_METHOD,
                        annotated,
                        "@ThriftEnumValue method '" + annotated.getSimpleName()
                                + "' must be declared by a Java enum or an interface inherited "
                                + "by an enum."));
            }
        }
    }

    private void addInheritedValueOwners(
            Element element,
            Map<String, ModelDemand> candidates) {
        if (element instanceof TypeElement && element.getKind() == ElementKind.ENUM) {
            TypeElement enumType = (TypeElement) element;
            for (ExecutableElement method : ElementFilter.methodsIn(
                    memberResolver.allMembers(enumType))) {
                for (ThriftAnnotationDialect dialect : ThriftAnnotationDialect.values()) {
                    if (ThriftAnnotations.has(method, dialect.thriftEnumValue())) {
                        registrar.add(enumType, SwiftModel.Kind.ENUM, dialect, candidates);
                    }
                }
            }
        }
        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed instanceof TypeElement) {
                addInheritedValueOwners(enclosed, candidates);
            }
        }
    }

    private TypeElement element(String name) {
        return environment.getElementUtils().getTypeElement(name);
    }
}
