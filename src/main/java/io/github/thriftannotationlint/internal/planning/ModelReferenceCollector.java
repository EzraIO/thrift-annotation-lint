package io.github.thriftannotationlint.internal.planning;

import io.github.thriftannotationlint.internal.extract.SwiftModelClassifier;
import io.github.thriftannotationlint.internal.model.ModelReference;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;
import io.github.thriftannotationlint.internal.types.ThriftTypeInspector;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Traverses a wire type and returns referenced model views in encounter order. */
final class ModelReferenceCollector {
    private final ThriftTypeInspector typeInspector;
    private final SwiftModelClassifier modelClassifier;

    ModelReferenceCollector(
            ThriftTypeInspector typeInspector,
            SwiftModelClassifier modelClassifier) {
        this.typeInspector = typeInspector;
        this.modelClassifier = modelClassifier;
    }

    List<ModelReference> collect(TypeMirror type, ThriftAnnotationDialect dialect) {
        List<ModelReference> references = new ArrayList<ModelReference>();
        collect(type, dialect, references, new LinkedHashSet<String>());
        return references;
    }

    private void collect(
            TypeMirror type,
            ThriftAnnotationDialect dialect,
            List<ModelReference> references,
            Set<String> visiting) {
        if (type == null || type.getKind() == TypeKind.ERROR) {
            return;
        }
        String visitKey = type.getKind() + ":" + type;
        if (!visiting.add(visitKey)) {
            return;
        }
        try {
            collectVisited(type, dialect, references, visiting);
        }
        finally {
            visiting.remove(visitKey);
        }
    }

    private void collectVisited(
            TypeMirror type,
            ThriftAnnotationDialect dialect,
            List<ModelReference> references,
            Set<String> visiting) {
        if (type.getKind() == TypeKind.TYPEVAR) {
            collectTypeVariable((TypeVariable) type, dialect, references, visiting);
            return;
        }
        if (type.getKind() == TypeKind.WILDCARD) {
            collectWildcard((WildcardType) type, dialect, references, visiting);
            return;
        }
        if (type.getKind() == TypeKind.INTERSECTION) {
            List<? extends TypeMirror> bounds = ((IntersectionType) type).getBounds();
            if (!bounds.isEmpty()) {
                collect(bounds.get(0), dialect, references, visiting);
            }
            return;
        }
        if (type.getKind() == TypeKind.DECLARED) {
            collectDeclared((DeclaredType) type, dialect, references, visiting);
        }
    }

    private void collectTypeVariable(
            TypeVariable type,
            ThriftAnnotationDialect dialect,
            List<ModelReference> references,
            Set<String> visiting) {
        if (typeInspector.isModelTypeVariable(type)) {
            return;
        }
        TypeMirror bound = firstUpperBound(type.getUpperBound());
        if (!addModelReference(type, bound, references)) {
            collect(bound, dialect, references, visiting);
        }
    }

    private void collectWildcard(
            WildcardType wildcard,
            ThriftAnnotationDialect dialect,
            List<ModelReference> references,
            Set<String> visiting) {
        TypeMirror bound = wildcard.getExtendsBound() == null
                ? wildcard.getSuperBound()
                : wildcard.getExtendsBound();
        if (!addModelReference(wildcard, firstUpperBound(bound), references)) {
            collect(bound, dialect, references, visiting);
        }
    }

    private void collectDeclared(
            DeclaredType declared,
            ThriftAnnotationDialect dialect,
            List<ModelReference> references,
            Set<String> visiting) {
        Element element = declared.asElement();
        TypeElement typeElement = element instanceof TypeElement ? (TypeElement) element : null;
        if (typeElement != null && typeElement.getKind() == ElementKind.ENUM) {
            references.add(new ModelReference(declared, declared));
            return;
        }
        List<TypeMirror> nestedArguments =
                typeInspector.nestedWireTypeArguments(declared, dialect);
        if (!nestedArguments.isEmpty()) {
            for (TypeMirror argument : nestedArguments) {
                collect(argument, dialect, references, visiting);
            }
            return;
        }
        if (typeElement != null && modelClassifier.modelKind(typeElement) != null) {
            references.add(new ModelReference(declared, declared));
        }
    }

    private boolean addModelReference(
            TypeMirror requestedType,
            TypeMirror modelView,
            List<ModelReference> references) {
        if (modelView == null || modelView.getKind() != TypeKind.DECLARED) {
            return false;
        }
        DeclaredType declared = (DeclaredType) modelView;
        Element element = declared.asElement();
        if (!(element instanceof TypeElement)) {
            return false;
        }
        TypeElement typeElement = (TypeElement) element;
        if (typeElement.getKind() != ElementKind.ENUM
                && typeInspector.isContainerType(modelView)) {
            return false;
        }
        if (modelClassifier.modelKind(typeElement) == null) {
            return false;
        }
        references.add(new ModelReference(requestedType, declared));
        return true;
    }

    private TypeMirror firstUpperBound(TypeMirror bound) {
        if (bound != null && bound.getKind() == TypeKind.INTERSECTION) {
            List<? extends TypeMirror> bounds = ((IntersectionType) bound).getBounds();
            return bounds.isEmpty() ? null : bounds.get(0);
        }
        return bound;
    }
}
