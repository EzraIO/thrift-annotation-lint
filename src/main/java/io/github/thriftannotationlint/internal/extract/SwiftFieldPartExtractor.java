package io.github.thriftannotationlint.internal.extract;

import io.github.thriftannotationlint.internal.model.SwiftMemberNames;

import io.github.thriftannotationlint.internal.model.ThriftAnnotations;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;

import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.model.FieldPart;
import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.model.ThriftFieldData;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import java.util.List;
import java.util.Set;

/** Extracts reflection-visible field/getter/setter/injection parameter metadata. */
final class SwiftFieldPartExtractor {
    private final Elements elements;
    private final SwiftMemberResolver memberResolver;
    private final ThriftParameterNameResolver parameterNameResolver;

    SwiftFieldPartExtractor(
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

    void extractAnnotatedFields(
            TypeElement root,
            DeclaredType rootType,
            ThriftAnnotationDialect dialect,
            boolean allowReaders,
            boolean allowWriters,
            List<FieldPart> parts,
            List<Finding> findings) {
        for (TypeElement hierarchyType : memberResolver.hierarchy(root)) {
            if (hierarchyType.getKind().isInterface()) {
                continue;
            }
            for (VariableElement field
                    : ElementFilter.fieldsIn(hierarchyType.getEnclosedElements())) {
                AnnotationMirror annotation =
                        ThriftAnnotations.find(field, dialect.thriftField());
                if (annotation == null) {
                    continue;
                }
                if (!memberResolver.isPublicInstance(field)) {
                    findings.add(Finding.error(
                            DiagnosticCode.INVALID_MEMBER_MODIFIERS,
                            field,
                            "@ThriftField field '" + field.getSimpleName()
                                    + "' must be public, non-static, and declared by a public type."));
                    continue;
                }
                if (allowWriters && field.getModifiers().contains(Modifier.FINAL)) {
                    findings.add(Finding.error(
                            DiagnosticCode.INVALID_MEMBER_MODIFIERS,
                            field,
                            "@ThriftField field '" + field.getSimpleName()
                                    + "' must not be final when Swift uses it as an injection path."));
                    continue;
                }
                parts.add(new FieldPart(
                        FieldPart.Source.FIELD,
                        field,
                        field,
                        field.getSimpleName().toString(),
                        memberResolver.resolveMemberType(rootType, field),
                        ThriftFieldData.from(elements, annotation),
                        allowReaders,
                        allowWriters));
            }
        }
    }

    void extractAnnotatedMethods(
            TypeElement root,
            DeclaredType rootType,
            SwiftModel.Kind modelKind,
            ThriftAnnotationDialect dialect,
            boolean allowReaders,
            boolean allowWriters,
            List<FieldPart> parts,
            Set<String> roundCompilationTypes,
            List<Finding> findings) {
        validateInvalidDeclaredMethods(root, dialect, findings);
        List<ExecutableElement> methods = memberResolver.effectiveMethods(
                root,
                dialect.thriftField(),
                true);
        for (ExecutableElement method : methods) {
            AnnotationMirror methodAnnotation =
                    ThriftAnnotations.find(method, dialect.thriftField());
            boolean annotatedParameters = memberResolver.hasAnnotatedParameter(
                    method, dialect.thriftField());

            if (!memberResolver.isPublicInstance(method)) {
                findings.add(Finding.error(
                        DiagnosticCode.INVALID_MEMBER_MODIFIERS,
                        method,
                        "Thrift injection method '" + method.getSimpleName()
                                + "' must be public, non-static, and declared by a public type."));
                continue;
            }
            if (methodAnnotation == null) {
                if (annotatedParameters) {
                    findings.add(Finding.error(
                            DiagnosticCode.INVALID_METHOD_OR_CONSTRUCTOR,
                            method,
                            "A method with @ThriftField parameters must also be annotated with "
                                    + "@ThriftField."));
                }
                continue;
            }

            SwiftMemberResolver.ResolvedExecutable resolved =
                    memberResolver.resolveExecutable(rootType, method);
            List<? extends TypeMirror> parameterTypes = resolved.parameterTypes();
            if (parameterTypes.isEmpty() && resolved.returnType().getKind() != TypeKind.VOID) {
                if (!allowReaders) {
                    findings.add(Finding.error(
                            DiagnosticCode.INVALID_METHOD_OR_CONSTRUCTOR,
                            method,
                            "Reader method '" + method.getSimpleName()
                                    + "' is not allowed on a Thrift builder."));
                    continue;
                }
                parts.add(new FieldPart(
                        FieldPart.Source.GETTER,
                        method,
                        method,
                        SwiftMemberNames.extractedFieldName(method.getSimpleName().toString()),
                        resolved.returnType(),
                        ThriftFieldData.from(elements, methodAnnotation),
                        true,
                        false));
                continue;
            }

            boolean validSetter = modelKind == SwiftModel.Kind.UNION
                    ? parameterTypes.size() == 1
                    : !parameterTypes.isEmpty();
            if (!validSetter) {
                findings.add(Finding.error(
                        DiagnosticCode.INVALID_METHOD_OR_CONSTRUCTOR,
                        method,
                        "@ThriftField method '" + method.getSimpleName()
                                + "' is not a supported getter or setter."));
                continue;
            }
            if (!allowWriters) {
                findings.add(Finding.error(
                        DiagnosticCode.INVALID_METHOD_OR_CONSTRUCTOR,
                        method,
                        "Injection method '" + method.getSimpleName()
                                + "' is not allowed on a Thrift model that declares a builder."));
                continue;
            }

            boolean parameterMode = parameterTypes.size() > 1
                    || (!method.getParameters().isEmpty()
                    && ThriftAnnotations.has(
                    method.getParameters().get(0),
                    dialect.thriftField()));
            if (parameterMode) {
                validateParameterInjectionMethod(method, methodAnnotation, findings);
                addParameters(
                        FieldPart.Source.METHOD_PARAMETER,
                        method,
                        parameterTypes,
                        dialect,
                        parts,
                        roundCompilationTypes,
                        findings);
            }
            else {
                VariableElement parameter = method.getParameters().get(0);
                parts.add(new FieldPart(
                        FieldPart.Source.SETTER,
                        parameter,
                        method,
                        SwiftMemberNames.extractedFieldName(method.getSimpleName().toString()),
                        parameterTypes.get(0),
                        ThriftFieldData.from(elements, methodAnnotation),
                        false,
                        true));
            }
        }
    }

    private void validateInvalidDeclaredMethods(
            TypeElement root,
            ThriftAnnotationDialect dialect,
            List<Finding> findings) {
        for (TypeElement hierarchyType : memberResolver.hierarchy(root)) {
            if (hierarchyType.getKind().isInterface()) {
                continue;
            }
            for (ExecutableElement method
                    : ElementFilter.methodsIn(hierarchyType.getEnclosedElements())) {
                if (!ThriftAnnotations.has(method, dialect.thriftField())
                        && !memberResolver.hasAnnotatedParameter(
                        method, dialect.thriftField())) {
                    continue;
                }
                if (!memberResolver.isPublicInstance(method)) {
                    findings.add(Finding.error(
                            DiagnosticCode.INVALID_MEMBER_MODIFIERS,
                            method,
                            "Thrift injection method '" + method.getSimpleName()
                                    + "' must be public, non-static, and declared by a public type."));
                }
            }
        }
    }

    private void validateParameterInjectionMethod(
            ExecutableElement method,
            AnnotationMirror annotation,
            List<Finding> findings) {
        ThriftFieldData data = ThriftFieldData.from(elements, annotation);
        if (data.id() != null) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_METHOD_OR_CONSTRUCTOR,
                    method,
                    annotation,
                    data.idSource(),
                    "A method with annotated parameters must not declare a field ID."));
        }
        if (data.explicitName() != null) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_METHOD_OR_CONSTRUCTOR,
                    method,
                    "A method with annotated parameters must not declare a field name."));
        }
        if ("REQUIRED".equals(data.requiredness())) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_METHOD_OR_CONSTRUCTOR,
                    method,
                    "A method with annotated parameters must not be marked required."));
        }
    }

    private void addParameters(
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
            VariableElement parameter = executable.getParameters().get(index);
            ThriftFieldData field = ThriftFieldData.from(elements, parameter, dialect);
            boolean stableIdentity = validateStableParameterIdentity(
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
                    parameterTypes.get(index),
                    field,
                    false,
                    true,
                    parameterNames.reliable() && stableIdentity,
                    noLvtName,
                    requiresIdBasedParameterMerge(
                            stableIdentity,
                            annotationNames,
                            extractedName,
                            parameterNames)));
        }
    }

    private boolean requiresIdBasedParameterMerge(
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
        // @Named preserves an empty value. A field ID can still reconcile that parameter.
        return extractedName != null && extractedName.isEmpty();
    }

    private boolean validateStableParameterIdentity(
            VariableElement parameter,
            ThriftFieldData field,
            ThriftParameterNameResolver.Result parameterNames,
            boolean stableAnnotationNames,
            ThriftAnnotationDialect dialect,
            List<Finding> findings) {
        if (field.id() != null
                || field.explicitName() != null
                || stableAnnotationNames) {
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
                        + "'s runtime parameter-name "
                        + "lookup is not guaranteed for this declaration."));
        return false;
    }
}
