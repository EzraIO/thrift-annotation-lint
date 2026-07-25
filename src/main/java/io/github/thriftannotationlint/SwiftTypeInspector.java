package io.github.thriftannotationlint;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.List;

/** Resolves Java types according to the supported unmodified Facebook Swift catalogs. */
final class SwiftTypeInspector {
    private final JavaTypeIdentityFormatter identityFormatter;
    private final SwiftCatalogTypeClassifier classifier;
    private final NormalizedTypeCompatibility compatibility;

    SwiftTypeInspector(Types types, Elements elements) {
        TypeHierarchyResolver hierarchyResolver = new TypeHierarchyResolver(types);
        this.identityFormatter = new JavaTypeIdentityFormatter();
        this.classifier = new SwiftCatalogTypeClassifier(
                elements, hierarchyResolver, identityFormatter);
        this.compatibility = new NormalizedTypeCompatibility(
                types, hierarchyResolver, classifier, identityFormatter);
    }

    boolean isSupported(TypeMirror type) {
        return classifier.isSupported(type);
    }

    /**
     * Returns the Java type identity used by Swift's normalized {@code ThriftType}.
     * Collection implementations intentionally collapse to their wire container interface, while
     * coerced scalar types (for example {@code int} and {@code Integer}) remain distinct.
     */
    String normalizedType(TypeMirror type) {
        return classifier.normalizedType(type);
    }

    String normalizedType(TypeMirror type, boolean preserveExactStructReference) {
        return preserveExactStructReference
                ? identityFormatter.javaTypeIdentity(type, true)
                : classifier.normalizedType(type);
    }

    String exactJavaTypeIdentity(TypeMirror type) {
        return identityFormatter.exactJavaTypeIdentity(type);
    }

    /**
     * Compares normalized types while treating a type variable as an unknown value that will be
     * resolved when a generic model is instantiated. Container structure must still match, and
     * callers must compare every pair in a logical field so a type variable cannot hide two
     * incompatible concrete declarations.
     */
    boolean areCompatibleNormalizedTypes(String left, String right) {
        return compatibility.areCompatibleNormalizedTypes(left, right);
    }

    boolean isContainerType(TypeMirror type) {
        return classifier.isContainerType(type);
    }

    /**
     * Returns a resolved type that proves Swift will classify the source declaration as a
     * container. javac can retain a stale root DeclaredType when a generated direct supertype is
     * completed in a later round, so the element hierarchy is used as a bounded fallback.
     */
    TypeMirror containerClassificationType(TypeElement type) {
        return containerClassificationType(type == null ? null : type.asType());
    }

    TypeMirror containerClassificationType(TypeMirror candidate) {
        return classifier.containerClassificationType(candidate);
    }

    List<TypeMirror> containerTypeArguments(TypeMirror type) {
        return classifier.containerTypeArguments(type);
    }

    /** Returns whether this extraction type can be passed to Swift's canonical codec shape. */
    boolean providesCanonicalValue(TypeMirror source) {
        return compatibility.providesCanonicalValue(source);
    }

    /** Returns whether Swift's canonical decoded value can be injected into this Java type. */
    boolean acceptsDecodedValue(TypeMirror target) {
        return compatibility.acceptsDecodedValue(target);
    }

    String canonicalDecodedTypeName(TypeMirror target) {
        return compatibility.canonicalDecodedTypeName(target);
    }

    boolean isModelTypeVariable(TypeMirror type) {
        return identityFormatter.isModelTypeVariable(type);
    }
}
