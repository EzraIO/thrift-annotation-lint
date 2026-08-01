package io.github.thriftannotationlint.internal.extract;

import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.model.ThriftAnnotations;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.List;

/** Resolves and binds the builder class declared by a model annotation. */
final class SwiftBuilderTypeResolver {
    private final Elements elements;
    private final Types types;

    SwiftBuilderTypeResolver(Elements elements, Types types) {
        this.elements = elements;
        this.types = types;
    }

    TypeElement extract(
            TypeElement modelType,
            AnnotationMirror modelAnnotation,
            List<Finding> findings) {
        if (modelAnnotation == null) {
            return null;
        }
        TypeMirror builderType = ThriftAnnotations.classValue(elements, modelAnnotation, "builder");
        if (ThriftAnnotations.isVoidClassValue(builderType)) {
            return null;
        }
        Element builderElement = types.asElement(builderType);
        if (!(builderElement instanceof TypeElement)) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_BUILDER,
                    modelType,
                    "Builder for Thrift model '" + modelType.getQualifiedName()
                            + "' cannot be resolved."));
            return null;
        }
        TypeElement builder = (TypeElement) builderElement;
        if ("java.lang.Void".contentEquals(builder.getQualifiedName())) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_BUILDER,
                    modelType,
                    modelAnnotation,
                    ThriftAnnotations.explicitValue(modelAnnotation, "builder"),
                    "Builder for Thrift model '" + modelType.getQualifiedName()
                            + "' is java.lang.Void; omit builder or use void.class for Swift's "
                            + "no-builder sentinel."));
            return null;
        }
        validateDeclaration(modelType, builder, findings);
        return builder;
    }

    DeclaredType bind(
            TypeElement model,
            DeclaredType modelType,
            TypeElement builder,
            List<Finding> findings) {
        if (builder == null) {
            return null;
        }
        List<? extends TypeParameterElement> builderParameters = builder.getTypeParameters();
        if (builderParameters.isEmpty()) {
            return (DeclaredType) builder.asType();
        }
        List<? extends TypeMirror> modelArguments = modelType.getTypeArguments();
        if (modelArguments.isEmpty()) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_BUILDER,
                    model,
                    "Generic builder '" + builder.getQualifiedName()
                            + "' requires a parameterized model type; raw model '"
                            + model.getQualifiedName() + "' cannot bind its type parameters."));
            return (DeclaredType) types.erasure(builder.asType());
        }
        if (builderParameters.size() != modelArguments.size()) {
            return (DeclaredType) builder.asType();
        }
        try {
            return types.getDeclaredType(
                    builder,
                    modelArguments.toArray(new TypeMirror[modelArguments.size()]));
        }
        catch (IllegalArgumentException ignored) {
            return (DeclaredType) builder.asType();
        }
    }

    private void validateDeclaration(
            TypeElement modelType,
            TypeElement builder,
            List<Finding> findings) {
        if (builder.getKind() != ElementKind.CLASS
                || !builder.getModifiers().contains(Modifier.PUBLIC)) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_BUILDER,
                    builder,
                    "Builder type '" + builder.getQualifiedName() + "' must be a public class."));
        }
        List<? extends TypeParameterElement> builderParameters = builder.getTypeParameters();
        if (!builderParameters.isEmpty()
                && builderParameters.size() != modelType.getTypeParameters().size()) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_BUILDER,
                    builder,
                    "Generic builder '" + builder.getQualifiedName()
                            + "' must declare the same number of type parameters as model '"
                            + modelType.getQualifiedName() + "'."));
        }
    }
}
