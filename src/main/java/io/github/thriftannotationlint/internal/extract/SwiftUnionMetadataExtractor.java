package io.github.thriftannotationlint.internal.extract;

import io.github.thriftannotationlint.internal.model.ThriftAnnotations;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;

import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.model.SwiftModel;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.ElementFilter;
import java.util.ArrayList;
import java.util.List;

/** Extracts and validates a union's single reflection-visible active-ID member. */
final class SwiftUnionMetadataExtractor {
    private final SwiftMemberResolver memberResolver;

    SwiftUnionMetadataExtractor(SwiftMemberResolver memberResolver) {
        this.memberResolver = memberResolver;
    }

    List<SwiftModel.ElementWithAnnotation> extract(
            TypeElement unionType,
            DeclaredType unionDeclaredType,
            ThriftAnnotationDialect dialect,
            List<Finding> findings) {
        List<SwiftModel.ElementWithAnnotation> candidates =
                new ArrayList<SwiftModel.ElementWithAnnotation>();
        for (TypeElement hierarchyType : memberResolver.hierarchy(unionType)) {
            if (hierarchyType.getKind().isInterface()) {
                continue;
            }
            for (VariableElement field
                    : ElementFilter.fieldsIn(hierarchyType.getEnclosedElements())) {
                AnnotationMirror annotation =
                        ThriftAnnotations.find(field, dialect.thriftUnionId());
                if (annotation == null) {
                    continue;
                }
                candidates.add(new SwiftModel.ElementWithAnnotation(field, annotation));
                if (!memberResolver.isPublicInstance(field)) {
                    findings.add(Finding.error(
                            DiagnosticCode.INVALID_MEMBER_MODIFIERS,
                            field,
                            "@ThriftUnionId field '" + field.getSimpleName()
                                    + "' must be public, non-static, and declared by a public type."));
                }
                if (field.getModifiers().contains(Modifier.FINAL)) {
                    findings.add(Finding.error(
                            DiagnosticCode.INVALID_MEMBER_MODIFIERS,
                            field,
                            "@ThriftUnionId field '" + field.getSimpleName()
                                    + "' must not be final because Swift injects the active ID."));
                }
            }
        }
        for (ExecutableElement method : memberResolver.effectiveMethods(
                unionType,
                dialect.thriftUnionId(),
                false)) {
            AnnotationMirror annotation =
                    ThriftAnnotations.find(method, dialect.thriftUnionId());
            candidates.add(new SwiftModel.ElementWithAnnotation(method, annotation));
            SwiftMemberResolver.ResolvedExecutable resolved =
                    memberResolver.resolveExecutable(unionDeclaredType, method);
            if (!memberResolver.isPublicInstance(method)
                    || !resolved.parameterTypes().isEmpty()
                    || resolved.returnType().getKind() == TypeKind.VOID) {
                findings.add(Finding.error(
                        DiagnosticCode.INVALID_UNION_ID,
                        method,
                        "@ThriftUnionId method '" + method.getSimpleName()
                                + "' must be public, non-static, declared by a public type, take no "
                                + "arguments, and return a value."));
            }
        }

        if (candidates.size() != 1) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_UNION_ID,
                    unionType,
                    "Thrift union '" + unionType.getQualifiedName()
                            + "' must declare exactly one @ThriftUnionId field or method; found "
                            + candidates.size() + "."));
        }
        return candidates;
    }
}
