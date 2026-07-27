package io.github.thriftannotationlint.internal.extract;

import io.github.thriftannotationlint.internal.bytecode.ClasspathParameterNames;
import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.model.ThriftAnnotations;
import io.github.thriftannotationlint.internal.model.ElementNames;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Resolves injection names with the parameter lookup rules of the selected codec dialect. */
final class ThriftParameterNameResolver {
    private final Elements elements;
    private final ClasspathParameterNames classpathParameterNames;

    ThriftParameterNameResolver(
            Elements elements,
            ClasspathParameterNames classpathParameterNames) {
        this.elements = elements;
        this.classpathParameterNames = classpathParameterNames;
    }

    Result resolve(
            ExecutableElement executable,
            ThriftAnnotationDialect dialect,
            Set<String> roundCompilationTypes,
            List<Finding> findings) {
        TypeElement owner = declaringType(executable);
        if (owner != null
                && roundCompilationTypes.contains(owner.getQualifiedName().toString())) {
            return new Result(
                    elementParameterNames(executable),
                    true,
                    true,
                    generalParameterNames(executable),
                    false,
                    true);
        }

        ClasspathParameterNames.LookupResult lookup = classpathParameterNames.find(executable);
        if (lookup.isInvalid()) {
            if (dialect == ThriftAnnotationDialect.AIRLIFT_DRIFT) {
                // Drift 1.18 catches bytecode lookup failures and falls back to reflection names.
                return new Result(
                        generalParameterNames(executable), true, true, null, true, true);
            }
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_METHOD_OR_CONSTRUCTOR,
                    executable,
                    "Cannot safely reproduce Swift parameter-name lookup for classpath "
                            + "executable '" + ElementNames.qualifiedMemberName(executable)
                            + "': " + lookup.failure() + "."));
            return Result.invalid(generalParameterNames(executable));
        }
        if (lookup.isFound()) {
            return new Result(
                    lookup.names(), true, false, null, false, true);
        }
        if (dialect == ThriftAnnotationDialect.AIRLIFT_DRIFT) {
            // ParameterNames falls back to reflection, which yields argN without -parameters.
            return new Result(
                    generalParameterNames(executable), true, true, null, true, true);
        }
        // Supported Swift releases ignore MethodParameters and deterministically fall back to
        // GeneralParanamer's argN names when no LocalVariableTable is present.
        return new Result(
                generalParameterNames(executable), true, true, null, true, true);
    }

    List<String> annotationNames(
            ExecutableElement executable,
            ThriftAnnotationDialect dialect) {
        List<String> names = new ArrayList<String>();
        for (VariableElement parameter : executable.getParameters()) {
            String name = null;
            for (AnnotationMirror annotation : parameter.getAnnotationMirrors()) {
                Element annotationElement = annotation.getAnnotationType().asElement();
                if (!(annotationElement instanceof TypeElement)) {
                    continue;
                }
                String annotationName =
                        ((TypeElement) annotationElement).getQualifiedName().toString();
                if (dialect.thriftField().equals(annotationName)) {
                    AnnotationValue configuredName =
                            ThriftAnnotations.explicitValue(annotation, "name");
                    String thriftName = ThriftAnnotations.stringValue(configuredName);
                    name = thriftName.isEmpty() ? null : thriftName;
                    break;
                }
                if (dialect == ThriftAnnotationDialect.FACEBOOK_SWIFT
                        && "javax.inject.Named".equals(annotationName)) {
                    // AnnotationParanamer preserves Named.value(), including its empty default.
                    name = ThriftAnnotations.stringValue(elements, annotation, "value");
                    break;
                }
            }
            if (name == null) {
                // ThriftFieldParanamer discards a partial array and AdaptiveParanamer advances.
                return null;
            }
            names.add(name);
        }
        return names;
    }

    Result annotationProvided(List<String> names) {
        return new Result(names, true, false, null, false, true);
    }

    private List<String> elementParameterNames(ExecutableElement executable) {
        List<String> names = new ArrayList<String>();
        for (VariableElement parameter : executable.getParameters()) {
            names.add(parameter.getSimpleName().toString());
        }
        return names;
    }

    private List<String> generalParameterNames(ExecutableElement executable) {
        List<String> names = new ArrayList<String>();
        for (int index = 0; index < executable.getParameters().size(); index++) {
            names.add("arg" + index);
        }
        return names;
    }

    private TypeElement declaringType(Element element) {
        Element current = element;
        while (current != null && !(current instanceof TypeElement)) {
            current = current.getEnclosingElement();
        }
        return current instanceof TypeElement ? (TypeElement) current : null;
    }

    static final class Result {
        private final List<String> names;
        private final boolean reliable;
        private final boolean requiresExplicitIdentity;
        private final List<String> noLvtNames;
        private final boolean idBasedMerge;
        private final boolean valid;

        private Result(
                List<String> names,
                boolean reliable,
                boolean requiresExplicitIdentity,
                List<String> noLvtNames,
                boolean idBasedMerge,
                boolean valid) {
            this.names = Collections.unmodifiableList(new ArrayList<String>(names));
            this.reliable = reliable;
            this.requiresExplicitIdentity = requiresExplicitIdentity;
            this.noLvtNames = noLvtNames == null
                    ? null
                    : Collections.unmodifiableList(new ArrayList<String>(noLvtNames));
            this.idBasedMerge = idBasedMerge;
            this.valid = valid;
        }

        static Result invalid(List<String> fallbackNames) {
            return new Result(fallbackNames, false, false, null, false, false);
        }

        List<String> names() {
            return names;
        }

        boolean reliable() {
            return reliable;
        }

        boolean requiresExplicitIdentity() {
            return requiresExplicitIdentity;
        }

        List<String> noLvtNames() {
            return noLvtNames;
        }

        boolean idBasedMerge() {
            return idBasedMerge;
        }

        boolean valid() {
            return valid;
        }
    }
}
