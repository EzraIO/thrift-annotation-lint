package io.github.thriftannotationlint.internal.extract;

import io.github.thriftannotationlint.internal.model.ThriftAnnotations;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;

import io.github.thriftannotationlint.internal.bytecode.ClasspathParameterNames;
import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.model.FieldPart;
import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.types.ThriftTypeInspector;
import io.github.thriftannotationlint.internal.types.UnresolvedSymbolInspector;
import io.github.thriftannotationlint.internal.types.IncompleteTypeGate;

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
    private final SwiftMemberResolver memberResolver;
    private final SwiftModelDeclarationValidator declarationValidator;

    public SwiftModelExtractor(
            ProcessingEnvironment processingEnvironment,
            ThriftTypeInspector typeInspector,
            IncompleteTypeGate incompleteTypeGate) {
        this(
                processingEnvironment,
                typeInspector,
                incompleteTypeGate,
                new SwiftMemberResolver(
                        processingEnvironment.getElementUtils(),
                        processingEnvironment.getTypeUtils()));
    }

    public SwiftModelExtractor(
            ProcessingEnvironment processingEnvironment,
            ThriftTypeInspector typeInspector,
            IncompleteTypeGate incompleteTypeGate,
            SwiftMemberResolver memberResolver) {
        this.elements = processingEnvironment.getElementUtils();
        Types types = processingEnvironment.getTypeUtils();

        this.memberResolver = memberResolver;
        this.declarationValidator = new SwiftModelDeclarationValidator(
                elements, memberResolver);
        ThriftParameterNameResolver parameterNameResolver = new ThriftParameterNameResolver(
                elements,
                new ClasspathParameterNames(processingEnvironment));
        this.unresolvedSymbolInspector = new UnresolvedSymbolInspector(
                elements, types, incompleteTypeGate);
        this.fieldPartExtractor = new SwiftFieldPartExtractor(
                elements, memberResolver, parameterNameResolver);
        this.constructionExtractor = new SwiftConstructionExtractor(
                elements, types, memberResolver, fieldPartExtractor);
        this.unionMetadataExtractor = new SwiftUnionMetadataExtractor(memberResolver);
        this.enumMetadataExtractor = new SwiftEnumMetadataExtractor(
                elements, types, memberResolver);
    }

    public void beginRound() {
        memberResolver.beginRound();
        unresolvedSymbolInspector.beginRound();
    }

    public ExtractionResult extract(
            DeclaredType declaredType,
            SwiftModel.Kind kind,
            String modelIdentity,
            String cacheKey,
            ThriftAnnotationDialect dialect,
            Set<String> roundCompilationTypes) {
        Element declaredElement = declaredType.asElement();
        if (!(declaredElement instanceof TypeElement)) {
            throw new IllegalArgumentException("Thrift model type must be declared");
        }
        TypeElement type = (TypeElement) declaredElement;
        boolean unresolvedSymbols = unresolvedSymbolInspector.hasUnresolvedSymbols(
                type, declaredType, kind, dialect);
        List<Finding> findings = new ArrayList<Finding>();
        declarationValidator.validate(type, kind, dialect, findings);

        if (kind == SwiftModel.Kind.ENUM) {
            List<ExecutableElement> enumMethods = enumMetadataExtractor.extract(
                    type, dialect, findings);
            return new ExtractionResult(
                    new SwiftModel(
                            kind,
                            type,
                            declaredType,
                            modelIdentity,
                            cacheKey,
                            dialect,
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
        declarationValidator.validateIdlAnnotations(type, modelAnnotation, findings);

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
                        cacheKey,
                        dialect,
                        builder,
                        parts,
                        constructionExecutables,
                        unionIds,
                        Collections.<ExecutableElement>emptyList()),
                findings,
                unresolvedSymbols);
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
