package io.github.thriftannotationlint.internal.types;

import io.github.thriftannotationlint.internal.model.ThriftAnnotations;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;
import io.github.thriftannotationlint.internal.model.SwiftModel;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.LinkedHashSet;
import java.util.Set;

/** Detects transient javac error types without retaining mirrors across processing rounds. */
public final class UnresolvedSymbolInspector {
    private final Elements elements;
    private final Types types;
    private final IncompleteTypeGate incompleteTypeGate;

    public UnresolvedSymbolInspector(Elements elements, Types types) {
        this(elements, types, new IncompleteTypeGate());
    }

    public UnresolvedSymbolInspector(
            Elements elements,
            Types types,
            IncompleteTypeGate incompleteTypeGate) {
        this.elements = elements;
        this.types = types;
        this.incompleteTypeGate = incompleteTypeGate;
    }

    public boolean hasUnresolvedSymbols(
            TypeElement type,
            DeclaredType declaredType,
            SwiftModel.Kind kind,
            ThriftAnnotationDialect dialect) {
        if (incompleteTypeGate.containsErrorType(declaredType)
                || hasUnresolvedHierarchy(type, new LinkedHashSet<String>())) {
            return true;
        }
        if (kind != SwiftModel.Kind.ENUM) {
            AnnotationMirror modelAnnotation = ThriftAnnotations.find(
                    type, dialect.modelAnnotation(kind));
            if (modelAnnotation != null) {
                TypeMirror builder = ThriftAnnotations.classValue(
                        elements, modelAnnotation, "builder");
                if (builder != null && builder.getKind() == TypeKind.ERROR) {
                    return true;
                }
                Element builderElement = builder == null ? null : types.asElement(builder);
                if (builderElement instanceof TypeElement
                        && !"void".equals(builder.toString())
                        && !"java.lang.Void".equals(builder.toString())
                        && hasUnresolvedHierarchy(
                        (TypeElement) builderElement,
                        new LinkedHashSet<String>())) {
                    return true;
                }
            }
        }
        return false;
    }

    public void beginRound() {
        incompleteTypeGate.beginRound();
    }

    private boolean hasUnresolvedHierarchy(TypeElement type, Set<String> visited) {
        String name = type.getQualifiedName().toString();
        if ("java.lang.Object".equals(name) || !visited.add(name)) {
            return false;
        }
        for (Element enclosed : type.getEnclosedElements()) {
            if (!(enclosed instanceof TypeElement)
                    && incompleteTypeGate.containsErrorType(enclosed.asType())) {
                return true;
            }
        }
        TypeMirror superclass = type.getSuperclass();
        if (incompleteTypeGate.containsErrorType(superclass)) {
            return true;
        }
        if (superclass.getKind() == TypeKind.DECLARED) {
            Element superElement = ((DeclaredType) superclass).asElement();
            if (superElement instanceof TypeElement
                    && hasUnresolvedHierarchy((TypeElement) superElement, visited)) {
                return true;
            }
        }
        for (TypeMirror interfaceType : type.getInterfaces()) {
            if (incompleteTypeGate.containsErrorType(interfaceType)) {
                return true;
            }
            if (interfaceType.getKind() == TypeKind.DECLARED) {
                Element interfaceElement = ((DeclaredType) interfaceType).asElement();
                if (interfaceElement instanceof TypeElement
                        && hasUnresolvedHierarchy((TypeElement) interfaceElement, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

}
