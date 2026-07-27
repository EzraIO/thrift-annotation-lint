package io.github.thriftannotationlint.internal.extract;

import io.github.thriftannotationlint.internal.model.ThriftAnnotations;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;

import io.github.thriftannotationlint.internal.bytecode.ClasspathParameterNames;
import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.model.FieldPart;
import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.types.SwiftTypeInspector;
import io.github.thriftannotationlint.internal.types.UnresolvedSymbolInspector;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Coordinates extraction of a source-level model mirroring Swift reflection metadata. */
public final class SwiftModelExtractor {
    private final Elements elements;
    private final UnresolvedSymbolInspector unresolvedSymbolInspector;
    private final SwiftFieldPartExtractor fieldPartExtractor;
    private final SwiftConstructionExtractor constructionExtractor;
    private final SwiftUnionMetadataExtractor unionMetadataExtractor;
    private final SwiftEnumMetadataExtractor enumMetadataExtractor;

    public SwiftModelExtractor(
            ProcessingEnvironment processingEnvironment,
            SwiftTypeInspector typeInspector) {
        this.elements = processingEnvironment.getElementUtils();
        Types types = processingEnvironment.getTypeUtils();

        SwiftMemberResolver memberResolver = new SwiftMemberResolver(elements, types);
        ThriftParameterNameResolver parameterNameResolver = new ThriftParameterNameResolver(
                elements,
                new ClasspathParameterNames(processingEnvironment));
        this.unresolvedSymbolInspector = new UnresolvedSymbolInspector(elements, types);
        this.fieldPartExtractor = new SwiftFieldPartExtractor(
                elements, memberResolver, parameterNameResolver);
        this.constructionExtractor = new SwiftConstructionExtractor(
                elements, types, memberResolver, fieldPartExtractor);
        this.unionMetadataExtractor = new SwiftUnionMetadataExtractor(memberResolver);
        this.enumMetadataExtractor = new SwiftEnumMetadataExtractor(
                elements, types, memberResolver);
    }

    public ExtractionResult extract(
            DeclaredType declaredType,
            SwiftModel.Kind kind,
            String modelIdentity,
            Set<String> roundCompilationTypes) {
        Element declaredElement = declaredType.asElement();
        if (!(declaredElement instanceof TypeElement)) {
            throw new IllegalArgumentException("Thrift model type must be declared");
        }
        TypeElement type = (TypeElement) declaredElement;
        ThriftAnnotationDialect dialect = modelDialect(type, kind);
        boolean unresolvedSymbols = unresolvedSymbolInspector.hasUnresolvedSymbols(
                type, declaredType, kind, dialect);
        List<Finding> findings = new ArrayList<Finding>();
        validateModelDeclaration(type, kind, findings);
        validateAnnotationDialect(type, dialect, findings);

        if (kind == SwiftModel.Kind.ENUM) {
            List<ExecutableElement> enumMethods = enumMetadataExtractor.extract(
                    type, dialect, findings);
            return new ExtractionResult(
                    new SwiftModel(
                            kind,
                            type,
                            declaredType,
                            modelIdentity,
                            null,
                            Collections.<FieldPart>emptyList(),
                            Collections.<ExecutableElement>emptyList(),
                            Collections.<SwiftModel.ElementWithAnnotation>emptyList(),
                            enumMethods),
                    findings,
                    unresolvedSymbols);
        }

        AnnotationMirror modelAnnotation = ThriftAnnotations.find(
                type, dialect.modelAnnotation(kind));
        validateIdlAnnotations(type, modelAnnotation, findings);

        TypeElement builder = constructionExtractor.extractBuilder(
                type, modelAnnotation, findings);
        DeclaredType builderType = constructionExtractor.bindBuilderType(
                type, declaredType, builder, findings);
        List<FieldPart> parts = new ArrayList<FieldPart>();
        List<ExecutableElement> constructionExecutables =
                new ArrayList<ExecutableElement>();

        if (builder == null) {
            constructionExtractor.extractConstructors(
                    type,
                    type,
                    declaredType,
                    kind,
                    dialect,
                    parts,
                    constructionExecutables,
                    roundCompilationTypes,
                    findings);
            fieldPartExtractor.extractAnnotatedFields(
                    type, declaredType, dialect, true, true, parts, findings);
            fieldPartExtractor.extractAnnotatedMethods(
                    type,
                    declaredType,
                    kind,
                    dialect,
                    true,
                    true,
                    parts,
                    roundCompilationTypes,
                    findings);
        }
        else {
            constructionExtractor.extractConstructors(
                    type,
                    builder,
                    builderType,
                    kind,
                    dialect,
                    parts,
                    constructionExecutables,
                    roundCompilationTypes,
                    findings);
            constructionExtractor.extractBuilderFactoryMethod(
                    type,
                    builder,
                    builderType,
                    dialect,
                    parts,
                    roundCompilationTypes,
                    findings);
            constructionExtractor.reportIgnoredStructConstructors(type, dialect, findings);

            fieldPartExtractor.extractAnnotatedFields(
                    type, declaredType, dialect, true, false, parts, findings);
            fieldPartExtractor.extractAnnotatedMethods(
                    type,
                    declaredType,
                    kind,
                    dialect,
                    true,
                    false,
                    parts,
                    roundCompilationTypes,
                    findings);

            fieldPartExtractor.extractAnnotatedFields(
                    builder, builderType, dialect, false, true, parts, findings);
            fieldPartExtractor.extractAnnotatedMethods(
                    builder,
                    builderType,
                    kind,
                    dialect,
                    false,
                    true,
                    parts,
                    roundCompilationTypes,
                    findings);
        }

        List<SwiftModel.ElementWithAnnotation> unionIds = kind == SwiftModel.Kind.UNION
                ? unionMetadataExtractor.extract(type, declaredType, dialect, findings)
                : Collections.<SwiftModel.ElementWithAnnotation>emptyList();

        Collections.sort(parts, new Comparator<FieldPart>() {
            @Override
            public int compare(FieldPart left, FieldPart right) {
                return left.sortKey().compareTo(right.sortKey());
            }
        });

        return new ExtractionResult(
                new SwiftModel(
                        kind,
                        type,
                        declaredType,
                        modelIdentity,
                        builder,
                        parts,
                        constructionExecutables,
                        unionIds,
                        Collections.<ExecutableElement>emptyList()),
                findings,
                unresolvedSymbols);
    }

    private void validateModelDeclaration(
            TypeElement type,
            SwiftModel.Kind kind,
            List<Finding> findings) {
        if (!type.getModifiers().contains(Modifier.PUBLIC)) {
            findings.add(Finding.error(
                    DiagnosticCode.MODEL_DECLARATION,
                    type,
                    "Thrift model type '" + type.getQualifiedName() + "' must be public."));
        }

        int modelAnnotations = ThriftAnnotations.modelAnnotationCount(type);
        if (modelAnnotations > 1) {
            findings.add(Finding.error(
                    DiagnosticCode.MODEL_DECLARATION,
                    type,
                    "Type '" + type.getQualifiedName()
                            + "' must not declare more than one Thrift model annotation."));
        }

        if (kind == SwiftModel.Kind.ENUM) {
            if (type.getKind() != ElementKind.ENUM) {
                String annotation = ThriftAnnotations.has(
                        type, modelDialect(type, kind).thriftEnum())
                        ? "@ThriftEnum"
                        : "@ThriftEnumValue";
                findings.add(Finding.error(
                        DiagnosticCode.INVALID_ENUM_VALUE_METHOD,
                        type,
                        annotation + " may only be used by a Java enum."));
            }
        }
        else if (type.getKind() != ElementKind.CLASS
                && !"RECORD".equals(type.getKind().name())) {
            findings.add(Finding.error(
                    DiagnosticCode.MODEL_DECLARATION,
                    type,
                    "@ThriftStruct and @ThriftUnion require a class or record declaration."));
        }
    }

    private void validateAnnotationDialect(
            TypeElement type,
            ThriftAnnotationDialect dialect,
            List<Finding> findings) {
        List<Element> elementsToCheck = new ArrayList<Element>();
        elementsToCheck.add(type);
        elementsToCheck.addAll(elements.getAllMembers(type));
        for (Element element : elementsToCheck) {
            for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
                Element annotationElement = annotation.getAnnotationType().asElement();
                if (!(annotationElement instanceof TypeElement)) {
                    continue;
                }
                String annotationName = ((TypeElement) annotationElement)
                        .getQualifiedName().toString();
                if (ThriftAnnotations.isSupportedAnnotation(annotationName)
                        && !dialect.ownsAnnotation(annotationName)) {
                    findings.add(Finding.error(
                            DiagnosticCode.MODEL_DECLARATION,
                            element,
                            annotation,
                            null,
                            "Thrift model '" + type.getQualifiedName() + "' uses "
                                    + dialect.displayName() + " annotations and must not mix in '"
                                    + annotationName + "'."));
                }
            }
        }
    }

    private ThriftAnnotationDialect modelDialect(
            TypeElement type,
            SwiftModel.Kind kind) {
        ThriftAnnotationDialect dialect = ThriftAnnotations.dialectFor(type, kind);
        if (dialect != null) {
            return dialect;
        }
        if (kind == SwiftModel.Kind.ENUM) {
            for (ThriftAnnotationDialect candidate : ThriftAnnotationDialect.values()) {
                for (Element member : elements.getAllMembers(type)) {
                    if (ThriftAnnotations.has(member, candidate.thriftEnumValue())
                            || (candidate.thriftEnumUnknownValue() != null
                            && ThriftAnnotations.has(
                                    member, candidate.thriftEnumUnknownValue()))) {
                        return candidate;
                    }
                }
            }
        }
        for (ThriftAnnotationDialect candidate : ThriftAnnotationDialect.values()) {
            if (elements.getTypeElement(candidate.modelAnnotation(kind)) != null) {
                return candidate;
            }
        }
        return ThriftAnnotationDialect.FACEBOOK_SWIFT;
    }

    private void validateIdlAnnotations(
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
                            + "' declares duplicate IDL annotation keys " + idl.duplicateKeys() + "."));
        }
    }

    public static final class ExtractionResult {
        private final SwiftModel model;
        private final List<Finding> findings;
        private final boolean unresolvedSymbols;

        private ExtractionResult(
                SwiftModel model,
                List<Finding> findings,
                boolean unresolvedSymbols) {
            this.model = model;
            this.findings = Collections.unmodifiableList(new ArrayList<Finding>(findings));
            this.unresolvedSymbols = unresolvedSymbols;
        }

        public SwiftModel model() {
            return model;
        }

        public List<Finding> findings() {
            return findings;
        }

        public boolean hasUnresolvedSymbols() {
            return unresolvedSymbols;
        }
    }
}
