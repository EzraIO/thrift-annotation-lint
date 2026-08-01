package io.github.thriftannotationlint.internal.types;

import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Resolves Java types according to the supported unmodified Facebook Swift catalogs. */
public final class ThriftTypeInspector {
    private final JavaTypeIdentityFormatter identityFormatter;
    private final WireTypeClassifier classifier;
    private final WireTypeSupport support;
    private final NormalizedTypeCompatibility compatibility;
    private final NormalizedWireTypeFormatter normalizedFormatter;
    private final CarrierShapeClassifier carrierShapeClassifier;
    private final Map<String, Boolean> supportedCache = new LinkedHashMap<String, Boolean>();
    private final Map<String, String> normalizedCache = new LinkedHashMap<String, String>();
    private final Set<String> normalizedMisses = new LinkedHashSet<String>();
    private final Map<String, String> carrierCache = new LinkedHashMap<String, String>();

    public ThriftTypeInspector(Types types, Elements elements) {
        this(types, elements, null);
    }

    ThriftTypeInspector(Types types, Elements elements, TypeInspectionMetrics metrics) {
        this.identityFormatter = new JavaTypeIdentityFormatter();
        TypeHierarchyResolver hierarchyResolver = new TypeHierarchyResolver(
                types, identityFormatter, metrics);
        this.classifier = new WireTypeClassifier(
                elements, hierarchyResolver, identityFormatter, metrics);
        this.support = new WireTypeSupport(classifier, identityFormatter);
        this.compatibility = new NormalizedTypeCompatibility(
                types, hierarchyResolver, classifier, identityFormatter);
        this.normalizedFormatter = new NormalizedWireTypeFormatter(
                classifier, identityFormatter);
        this.carrierShapeClassifier = new CarrierShapeClassifier(
                classifier, identityFormatter);
    }

    public void beginRound() {
        supportedCache.clear();
        normalizedCache.clear();
        normalizedMisses.clear();
        carrierCache.clear();
        classifier.beginRound();
    }

    public boolean isSupported(TypeMirror type) {
        return support.isSupported(type);
    }

    public boolean isSupported(TypeMirror type, ThriftAnnotationDialect dialect) {
        String key = cacheKey(type, dialect);
        Boolean cached = supportedCache.get(key);
        if (cached == null) {
            cached = Boolean.valueOf(support.isSupported(type, dialect));
            supportedCache.put(key, cached);
        }
        return cached.booleanValue();
    }

    /**
     * Returns the Java type identity used by Swift's normalized {@code ThriftType}.
     * Collection implementations intentionally collapse to their wire container interface, while
     * coerced scalar types (for example {@code int} and {@code Integer}) remain distinct.
     */
    public String normalizedType(TypeMirror type) {
        return normalizedFormatter.format(type);
    }

    public String normalizedType(TypeMirror type, boolean preserveExactStructReference) {
        return preserveExactStructReference
                ? identityFormatter.javaTypeIdentity(type, true)
                : normalizedFormatter.format(type);
    }

    public String normalizedType(
            TypeMirror type,
            boolean preserveExactStructReference,
            ThriftAnnotationDialect dialect) {
        return preserveExactStructReference
                ? identityFormatter.javaTypeIdentity(type, true)
                : normalizedTypeCached(type, dialect);
    }

    public String carrierShape(
            TypeMirror type,
            ThriftAnnotationDialect dialect) {
        String key = cacheKey(type, dialect);
        String cached = carrierCache.get(key);
        if (cached == null) {
            cached = carrierShapeClassifier.classify(type, dialect);
            carrierCache.put(key, cached);
        }
        return cached;
    }

    public String exactJavaTypeIdentity(TypeMirror type) {
        return identityFormatter.exactJavaTypeIdentity(type);
    }

    /**
     * Compares normalized types while treating a type variable as an unknown value that will be
     * resolved when a generic model is instantiated. Container structure must still match, and
     * callers must compare every pair in a logical field so a type variable cannot hide two
     * incompatible concrete declarations.
     */
    public boolean areCompatibleNormalizedTypes(String left, String right) {
        return compatibility.areCompatibleNormalizedTypes(left, right);
    }

    public boolean areCompatibleCarrierShapes(String left, String right) {
        return compatibility.areCompatibleCarrierShapes(left, right);
    }

    public boolean isContainerType(TypeMirror type) {
        return classifier.isContainerType(type);
    }

    /**
     * Returns a resolved type that proves Swift will classify the source declaration as a
     * container. javac can retain a stale root DeclaredType when a generated direct supertype is
     * completed in a later round, so the element hierarchy is used as a bounded fallback.
     */
    public TypeMirror containerClassificationType(TypeElement type) {
        return containerClassificationType(type == null ? null : type.asType());
    }

    public TypeMirror containerClassificationType(TypeMirror candidate) {
        return classifier.containerClassificationType(candidate);
    }

    public List<TypeMirror> containerTypeArguments(TypeMirror type) {
        return classifier.containerTypeArguments(type);
    }

    public List<TypeMirror> nestedWireTypeArguments(
            TypeMirror type,
            ThriftAnnotationDialect dialect) {
        return classifier.nestedWireTypeArguments(type, dialect);
    }

    /** Returns whether this extraction type can be passed to Swift's canonical codec shape. */
    public boolean providesCanonicalValue(TypeMirror source) {
        return compatibility.providesCanonicalValue(source);
    }

    public boolean providesCanonicalValue(
            TypeMirror source,
            ThriftAnnotationDialect dialect) {
        return compatibility.providesCanonicalValue(source, dialect);
    }

    /** Returns whether Swift's canonical decoded value can be injected into this Java type. */
    public boolean acceptsDecodedValue(TypeMirror target) {
        return compatibility.acceptsDecodedValue(target);
    }

    public boolean acceptsDecodedValue(
            TypeMirror target,
            ThriftAnnotationDialect dialect) {
        return compatibility.acceptsDecodedValue(target, dialect);
    }

    public String canonicalDecodedTypeName(TypeMirror target) {
        return compatibility.canonicalDecodedTypeName(target);
    }

    public String canonicalDecodedTypeName(
            TypeMirror target,
            ThriftAnnotationDialect dialect) {
        return compatibility.canonicalDecodedTypeName(target, dialect);
    }

    public boolean isModelTypeVariable(TypeMirror type) {
        return identityFormatter.isModelTypeVariable(type);
    }

    private String normalizedTypeCached(
            TypeMirror type,
            ThriftAnnotationDialect dialect) {
        String key = cacheKey(type, dialect);
        String cached = normalizedCache.get(key);
        if (cached != null || normalizedMisses.contains(key)) {
            return cached;
        }
        cached = normalizedFormatter.format(type, dialect);
        if (cached == null) {
            normalizedMisses.add(key);
        }
        else {
            normalizedCache.put(key, cached);
        }
        return cached;
    }

    private String cacheKey(TypeMirror type, ThriftAnnotationDialect dialect) {
        return dialect.name() + '\u0000' + identityFormatter.exactJavaTypeIdentity(type);
    }
}
