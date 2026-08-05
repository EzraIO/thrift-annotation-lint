package io.github.thriftannotationlint.internal.extract;

import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;
import io.github.thriftannotationlint.internal.model.ThriftAnnotations;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import java.util.ArrayList;
import java.util.List;

/** Validates model-level declarations before member metadata is extracted. */
final class SwiftModelDeclarationValidator {
    private final Elements elements;
    private final SwiftMemberResolver memberResolver;

    SwiftModelDeclarationValidator(Elements elements, SwiftMemberResolver memberResolver) {
        this.elements = elements;
        this.memberResolver = memberResolver;
    }

    void validate(
            TypeElement type,
            SwiftModel.Kind kind,
            ThriftAnnotationDialect dialect,
            List<Finding> findings) {
        validateDeclaration(type, kind, dialect, findings);
        validateAnnotationDialect(type, kind, dialect, findings);
    }

    void validateIdlAnnotations(
            TypeElement type,
            AnnotationMirror annotation,
            List<Finding> findings) {
        if (annotation == null) {
            return;
        }
        ThriftAnnotations.IdlAnnotations idl =
                ThriftAnnotations.readIdlAnnotations(elements, annotation, "idlAnnotations");
        if (!idl.duplicateKeys().isEmpty()) {
            findings.add(Finding.error(
                    DiagnosticCode.CONFLICTING_IDL_ANNOTATIONS,
                    type,
                    annotation,
                    idl.sourceValue(),
                    "Thrift model '" + type.getQualifiedName()
                            + "' declares duplicate IDL annotation keys "
                            + idl.duplicateKeys() + "."));
        }
    }

    private void validateDeclaration(
            TypeElement type,
            SwiftModel.Kind kind,
            ThriftAnnotationDialect dialect,
            List<Finding> findings) {
        if (!type.getModifiers().contains(Modifier.PUBLIC)) {
            findings.add(Finding.error(
                    DiagnosticCode.MODEL_DECLARATION,
                    type,
                    "Thrift model type '" + type.getQualifiedName() + "' must be public."));
        }
        if (ThriftAnnotations.modelAnnotationCount(type) > 1) {
            findings.add(Finding.error(
                    DiagnosticCode.MODEL_DECLARATION,
                    type,
                    "Type '" + type.getQualifiedName()
                            + "' must not declare more than one Thrift model annotation."));
        }
        if (kind == SwiftModel.Kind.ENUM) {
            validateEnumDeclaration(type, dialect, findings);
        }
        else if (type.getKind() != ElementKind.CLASS
                && !"RECORD".equals(type.getKind().name())) {
            findings.add(Finding.error(
                    DiagnosticCode.MODEL_DECLARATION,
                    type,
                    "@ThriftStruct and @ThriftUnion require a class or record declaration."));
        }
    }

    private void validateEnumDeclaration(
            TypeElement type,
            ThriftAnnotationDialect dialect,
            List<Finding> findings) {
        if (type.getKind() == ElementKind.ENUM) {
            if (dialect.runtime().enumPolicy().requiresModelAnnotation()
                    && !ThriftAnnotations.has(type, dialect.thriftEnum())) {
                findings.add(Finding.error(
                        DiagnosticCode.MODEL_DECLARATION,
                        type,
                        dialect.runtimeName() + " enum '" + type.getQualifiedName()
                                + "' must declare @ThriftEnum from '"
                                + dialect.thriftEnum() + "'."));
            }
            return;
        }
        String annotation = ThriftAnnotations.has(type, dialect.thriftEnum())
                ? "@ThriftEnum"
                : "@ThriftEnumValue";
        findings.add(Finding.error(
                DiagnosticCode.INVALID_ENUM_VALUE_METHOD,
                type,
                annotation + " may only be used by a Java enum."));
    }

    private void validateAnnotationDialect(
            TypeElement type,
            SwiftModel.Kind kind,
            ThriftAnnotationDialect dialect,
            List<Finding> findings) {
        boolean inheritedPlainEnumDialect = kind == SwiftModel.Kind.ENUM
                && ThriftAnnotations.dialectFor(type, kind) == null;
        List<Element> elementsToCheck = new ArrayList<Element>();
        elementsToCheck.add(type);
        elementsToCheck.addAll(memberResolver.allMembers(type));
        for (Element element : elementsToCheck) {
            validateElementDialect(
                    type, element, dialect, inheritedPlainEnumDialect, findings);
        }
    }

    private void validateElementDialect(
            TypeElement model,
            Element element,
            ThriftAnnotationDialect dialect,
            boolean inheritedPlainEnumDialect,
            List<Finding> findings) {
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            Element annotationElement = annotation.getAnnotationType().asElement();
            if (!(annotationElement instanceof TypeElement)) {
                continue;
            }
            String annotationName = ((TypeElement) annotationElement)
                    .getQualifiedName().toString();
            if (!ThriftAnnotations.isSupportedAnnotation(annotationName)
                    || dialect.ownsAnnotation(annotationName)
                    || isIndependentEnumMetadata(inheritedPlainEnumDialect, annotationName)) {
                continue;
            }
            findings.add(Finding.error(
                    DiagnosticCode.MODEL_DECLARATION,
                    element,
                    annotation,
                    null,
                    "Thrift model '" + model.getQualifiedName() + "' uses "
                            + dialect.displayName() + " annotations and must not mix in '"
                            + annotationName + "'."));
        }
    }

    private boolean isIndependentEnumMetadata(boolean inheritedPlainEnumDialect, String name) {
        return inheritedPlainEnumDialect
                && (name.endsWith(".ThriftEnumValue")
                || name.endsWith(".ThriftEnumUnknownValue"));
    }
}
