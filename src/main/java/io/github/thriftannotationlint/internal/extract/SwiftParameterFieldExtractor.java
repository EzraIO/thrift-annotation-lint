package io.github.thriftannotationlint.internal.extract;

import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.model.FieldPart;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;
import io.github.thriftannotationlint.internal.model.ThriftFieldData;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import java.util.List;
import java.util.Set;

/** Converts executable parameters into stable logical-field parts. */
final class SwiftParameterFieldExtractor {
    private final Elements elements;
    private final SwiftMemberResolver memberResolver;
    private final ThriftParameterNameResolver parameterNameResolver;

    SwiftParameterFieldExtractor(
            Elements elements,
            SwiftMemberResolver memberResolver,
            ThriftParameterNameResolver parameterNameResolver) {
        this.elements = elements;
        this.memberResolver = memberResolver;
        this.parameterNameResolver = parameterNameResolver;
    }

    void addExecutableParameters(
            DeclaredType root,
            ExecutableElement executable,
            ThriftAnnotationDialect dialect,
            List<FieldPart> parts,
            Set<String> roundCompilationTypes,
            List<Finding> findings) {
        SwiftMemberResolver.ResolvedExecutable resolved =
                memberResolver.resolveExecutable(root, executable);
        addParameters(
                FieldPart.Source.CONSTRUCTOR_PARAMETER,
                executable,
                resolved.parameterTypes(),
                dialect,
                parts,
                roundCompilationTypes,
                findings);
    }

    void addParameters(
            FieldPart.Source source,
            ExecutableElement executable,
            List<? extends TypeMirror> parameterTypes,
            ThriftAnnotationDialect dialect,
            List<FieldPart> parts,
            Set<String> roundCompilationTypes,
            List<Finding> findings) {
        List<String> annotationNames = parameterNameResolver.annotationNames(executable, dialect);
        ThriftParameterNameResolver.Result parameterNames = annotationNames == null
                ? parameterNameResolver.resolve(
                        executable, dialect, roundCompilationTypes, findings)
                : parameterNameResolver.annotationProvided(annotationNames);
        if (!parameterNames.valid()) {
            return;
        }
        for (int index = 0; index < executable.getParameters().size(); index++) {
            addParameter(
                    source,
                    executable,
                    executable.getParameters().get(index),
                    parameterTypes.get(index),
                    index,
                    dialect,
                    annotationNames,
                    parameterNames,
                    parts,
                    findings);
        }
    }

    private void addParameter(
            FieldPart.Source source,
            ExecutableElement executable,
            VariableElement parameter,
            TypeMirror parameterType,
            int index,
            ThriftAnnotationDialect dialect,
            List<String> annotationNames,
            ThriftParameterNameResolver.Result parameterNames,
            List<FieldPart> parts,
            List<Finding> findings) {
        ThriftFieldData field = ThriftFieldData.from(elements, parameter, dialect);
        boolean stableIdentity = validateStableIdentity(
                parameter,
                field,
                parameterNames,
                annotationNames != null,
                dialect,
                findings);
        String extractedName = annotationNames == null
                ? parameterNames.names().get(index)
                : annotationNames.get(index);
        String noLvtName = stableIdentity
                && annotationNames == null
                && parameterNames.noLvtNames() != null
                ? parameterNames.noLvtNames().get(index)
                : null;
        parts.add(new FieldPart(
                source,
                parameter,
                executable,
                extractedName,
                parameterType,
                field,
                false,
                true,
                parameterNames.reliable() && stableIdentity,
                noLvtName,
                requiresIdBasedMerge(
                        stableIdentity, annotationNames, extractedName, parameterNames)));
    }

    private boolean requiresIdBasedMerge(
            boolean stableIdentity,
            List<String> annotationNames,
            String extractedName,
            ThriftParameterNameResolver.Result parameterNames) {
        if (!stableIdentity) {
            return false;
        }
        if (annotationNames == null) {
            return parameterNames.idBasedMerge();
        }
        return extractedName != null && extractedName.isEmpty();
    }

    private boolean validateStableIdentity(
            VariableElement parameter,
            ThriftFieldData field,
            ThriftParameterNameResolver.Result parameterNames,
            boolean stableAnnotationNames,
            ThriftAnnotationDialect dialect,
            List<Finding> findings) {
        if (field.id() != null || field.explicitName() != null || stableAnnotationNames) {
            return true;
        }
        if (!parameterNames.requiresExplicitIdentity()) {
            return true;
        }
        findings.add(Finding.error(
                DiagnosticCode.INVALID_METHOD_OR_CONSTRUCTOR,
                parameter,
                field.annotation(),
                null,
                "Injection parameter '" + parameter.getSimpleName()
                        + "' must declare an explicit @ThriftField ID/name or a stable "
                        + "annotation-provided name because " + dialect.runtimeName()
                        + "'s runtime parameter-name lookup is not guaranteed for this declaration."));
        return false;
    }
}
