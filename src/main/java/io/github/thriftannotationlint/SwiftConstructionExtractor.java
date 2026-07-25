package io.github.thriftannotationlint;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Extracts Swift construction metadata for direct models and builder-backed models. */
final class SwiftConstructionExtractor {
    private final Elements elements;
    private final Types types;
    private final SwiftMemberResolver memberResolver;
    private final SwiftFieldPartExtractor fieldPartExtractor;

    SwiftConstructionExtractor(
            Elements elements,
            Types types,
            SwiftMemberResolver memberResolver,
            SwiftFieldPartExtractor fieldPartExtractor) {
        this.elements = elements;
        this.types = types;
        this.memberResolver = memberResolver;
        this.fieldPartExtractor = fieldPartExtractor;
    }

    TypeElement extractBuilder(
            TypeElement modelType,
            AnnotationMirror modelAnnotation,
            List<Finding> findings) {
        if (modelAnnotation == null) {
            return null;
        }
        TypeMirror builderType = SwiftAnnotations.classValue(elements, modelAnnotation, "builder");
        if (SwiftAnnotations.isVoidClassValue(builderType)) {
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
                    SwiftAnnotations.explicitValue(modelAnnotation, "builder"),
                    "Builder for Thrift model '" + modelType.getQualifiedName()
                            + "' is java.lang.Void; omit builder or use void.class for Swift's "
                            + "no-builder sentinel."));
            return null;
        }
        if (builder.getKind() != javax.lang.model.element.ElementKind.CLASS
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
        return builder;
    }

    DeclaredType bindBuilderType(
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

    void extractConstructors(
            TypeElement modelType,
            TypeElement constructionType,
            DeclaredType constructionView,
            SwiftModel.Kind modelKind,
            List<FieldPart> parts,
            List<ExecutableElement> constructionExecutables,
            Set<String> roundCompilationTypes,
            List<Finding> findings) {
        boolean isBuilder = !types.isSameType(modelType.asType(), constructionType.asType());
        if (constructionType.getModifiers().contains(Modifier.ABSTRACT)) {
            findings.add(Finding.error(
                    isBuilder
                            ? DiagnosticCode.INVALID_BUILDER
                            : DiagnosticCode.INVALID_METHOD_OR_CONSTRUCTOR,
                    constructionType,
                    (isBuilder ? "Builder type '" : "Thrift construction type '")
                            + constructionType.getQualifiedName()
                            + "' must be concrete so Swift can instantiate it."));
            return;
        }
        if (memberResolver.requiresEnclosingInstance(constructionType)) {
            findings.add(Finding.error(
                    isBuilder
                            ? DiagnosticCode.INVALID_BUILDER
                            : DiagnosticCode.INVALID_METHOD_OR_CONSTRUCTOR,
                    constructionType,
                    (isBuilder ? "Builder type '" : "Thrift construction type '")
                            + constructionType.getQualifiedName()
                            + "' must be top-level or static because a non-static member class "
                            + "has an implicit enclosing-instance constructor parameter."));
            return;
        }

        List<ExecutableElement> constructors =
                ElementFilter.constructorsIn(constructionType.getEnclosedElements());
        List<ExecutableElement> annotated = new ArrayList<ExecutableElement>();
        for (ExecutableElement constructor : constructors) {
            if (!SwiftAnnotations.has(constructor, SwiftAnnotations.THRIFT_CONSTRUCTOR)) {
                continue;
            }
            if (!constructor.getModifiers().contains(Modifier.PUBLIC)) {
                findings.add(Finding.error(
                        DiagnosticCode.INVALID_MEMBER_MODIFIERS,
                        constructor,
                        "@ThriftConstructor '" + constructor + "' must be public."));
                continue;
            }
            annotated.add(constructor);
        }

        if (annotated.isEmpty()) {
            ExecutableElement noArgumentConstructor = null;
            for (ExecutableElement constructor : constructors) {
                if (constructor.getParameters().isEmpty()) {
                    noArgumentConstructor = constructor;
                    break;
                }
            }
            if (noArgumentConstructor == null
                    || !noArgumentConstructor.getModifiers().contains(Modifier.PUBLIC)) {
                findings.add(Finding.error(
                        DiagnosticCode.INVALID_METHOD_OR_CONSTRUCTOR,
                        constructionType,
                        "Thrift construction type '" + constructionType.getQualifiedName()
                                + "' must declare a public no-argument constructor or a public "
                                + "@ThriftConstructor."));
            }
            else {
                constructionExecutables.add(noArgumentConstructor);
            }
            return;
        }

        if (modelKind == SwiftModel.Kind.STRUCT && annotated.size() > 1) {
            findings.add(Finding.error(
                    DiagnosticCode.MULTIPLE_THRIFT_CONSTRUCTORS,
                    annotated.get(1),
                    "Thrift struct '" + modelType.getQualifiedName()
                            + "' has multiple constructors annotated with @ThriftConstructor."));
        }

        for (ExecutableElement constructor : annotated) {
            constructionExecutables.add(constructor);
            if (modelKind == SwiftModel.Kind.UNION && constructor.getParameters().size() > 1) {
                findings.add(Finding.error(
                        DiagnosticCode.INVALID_UNION_CONSTRUCTOR,
                        constructor,
                        "@ThriftConstructor for union '" + modelType.getQualifiedName()
                                + "' may declare at most one parameter."));
            }
            fieldPartExtractor.addExecutableParameters(
                    constructionView,
                    constructor,
                    parts,
                    roundCompilationTypes,
                    findings);
        }
    }

    void extractBuilderFactoryMethod(
            TypeElement modelType,
            TypeElement builder,
            DeclaredType builderType,
            List<FieldPart> parts,
            Set<String> roundCompilationTypes,
            List<Finding> findings) {
        validateInvalidBuilderFactoryMethods(builder, findings);
        List<ExecutableElement> relevant = memberResolver.effectiveMethods(
                builder,
                SwiftAnnotations.THRIFT_CONSTRUCTOR,
                false);
        List<ExecutableElement> valid = new ArrayList<ExecutableElement>();
        for (ExecutableElement method : relevant) {
            if (!memberResolver.isPublicInstance(method)) {
                findings.add(Finding.error(
                        DiagnosticCode.INVALID_MEMBER_MODIFIERS,
                        method,
                        "Builder @ThriftConstructor method '" + method.getSimpleName()
                                + "' must be public, non-static, and declared by a public type."));
                continue;
            }
            valid.add(method);
        }

        if (valid.size() != 1) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_BUILDER,
                    builder,
                    "Builder '" + builder.getQualifiedName()
                            + "' must declare exactly one public, non-static @ThriftConstructor method."));
        }

        for (ExecutableElement method : valid) {
            SwiftMemberResolver.ResolvedExecutable resolved =
                    memberResolver.resolveExecutable(builderType, method);
            if (!types.isAssignable(
                    types.erasure(resolved.returnType()),
                    types.erasure(modelType.asType()))) {
                findings.add(Finding.error(
                        DiagnosticCode.INVALID_BUILDER,
                        method,
                        "Builder method '" + method.getSimpleName() + "' returns '"
                                + resolved.returnType() + "', which is not assignable to model '"
                                + modelType.getQualifiedName() + "'."));
            }
            fieldPartExtractor.addExecutableParameters(
                    builderType,
                    method,
                    parts,
                    roundCompilationTypes,
                    findings);
        }
    }

    void reportIgnoredStructConstructors(
            TypeElement modelType,
            List<Finding> findings) {
        for (ExecutableElement constructor
                : ElementFilter.constructorsIn(modelType.getEnclosedElements())) {
            if (SwiftAnnotations.has(constructor, SwiftAnnotations.THRIFT_CONSTRUCTOR)) {
                if (!constructor.getModifiers().contains(Modifier.PUBLIC)) {
                    continue;
                }
                findings.add(Finding.warning(
                        DiagnosticCode.INVALID_BUILDER,
                        constructor,
                        "Thrift model '" + modelType.getQualifiedName()
                                + "' declares a builder, so this @ThriftConstructor is ignored by Swift."));
            }
        }
    }

    private void validateInvalidBuilderFactoryMethods(
            TypeElement builder,
            List<Finding> findings) {
        for (TypeElement hierarchyType : memberResolver.hierarchy(builder)) {
            if (hierarchyType.getKind().isInterface()) {
                continue;
            }
            for (ExecutableElement method
                    : ElementFilter.methodsIn(hierarchyType.getEnclosedElements())) {
                if (SwiftAnnotations.has(method, SwiftAnnotations.THRIFT_CONSTRUCTOR)
                        && !memberResolver.isPublicInstance(method)) {
                    findings.add(Finding.error(
                            DiagnosticCode.INVALID_MEMBER_MODIFIERS,
                            method,
                            "Builder @ThriftConstructor method '" + method.getSimpleName()
                                    + "' must be public, non-static, and declared by a public type."));
                }
            }
        }
    }
}
