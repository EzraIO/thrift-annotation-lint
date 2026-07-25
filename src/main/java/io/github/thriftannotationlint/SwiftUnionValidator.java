package io.github.thriftannotationlint;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies Swift union-specific discriminator, payload, and construction rules. */
final class SwiftUnionValidator {
    private final Types types;

    SwiftUnionValidator(Types types) {
        this.types = types;
    }

    void validateDiscriminatorCollisions(
            SwiftModel model,
            ResolvedLogicalFields resolvedFields,
            List<Finding> findings) {
        Set<String> discriminatorExtractedNames = new LinkedHashSet<String>();
        for (SwiftModel.ElementWithAnnotation unionId : model.unionIds()) {
            discriminatorExtractedNames.add(
                    SwiftMemberNames.extractedFieldName(unionId.element()));
        }
        for (ResolvedLogicalFields.LogicalField field : resolvedFields.fields()) {
            for (FieldPart part : field.parts()) {
                boolean explicitPassCollision = part.isNameReliable(true)
                        && "_union_id".equals(part.explicitOrExtractedName());
                boolean extractedPassCollision = part.isExtractedNameReliable()
                        && discriminatorExtractedNames.contains(part.extractedName());
                if (explicitPassCollision || extractedPassCollision) {
                    findings.add(Finding.error(
                            DiagnosticCode.CONFLICTING_FIELD_ID,
                            part.element(),
                            "Thrift union '" + model.displayName() + "' field '"
                                    + field.displayName() + "' collides with Swift's internal "
                                    + "union discriminator during "
                                    + (explicitPassCollision
                                    ? "explicit-name"
                                    : "extracted-name")
                                    + " ID inference."));
                    break;
                }
            }
        }
    }

    void validate(
            SwiftModel model,
            ResolvedLogicalFields resolvedFields,
            List<Finding> findings) {
        validateUnionIds(model, findings);
        validateUnionPayloadIds(model, resolvedFields, findings);
        validateUnionConstruction(model, resolvedFields, findings);
    }

    private void validateUnionIds(SwiftModel model, List<Finding> findings) {
        for (SwiftModel.ElementWithAnnotation unionId : model.unionIds()) {
            Element element = unionId.element();
            if (model.builder() != null && element instanceof VariableElement) {
                Element declaringElement = element.getEnclosingElement();
                if (declaringElement instanceof TypeElement
                        && !types.isAssignable(
                        types.erasure(model.builder().asType()),
                        types.erasure(declaringElement.asType()))) {
                    findings.add(Finding.error(
                            DiagnosticCode.INVALID_UNION_ID,
                            element,
                            "Field-based @ThriftUnionId in union '" + model.displayName()
                                    + "' cannot be injected into builder '"
                                    + model.builder().getQualifiedName()
                                    + "' during Swift decoding; use a discriminator method or "
                                    + "a builder assignable to the field's declaring type."));
                }
            }
            TypeMirror type = resolvedUnionIdType(model.declaredType(), element);
            if (type != null
                    && type.getKind() != TypeKind.VOID
                    && type.getKind() != TypeKind.ERROR
                    && type.getKind() != TypeKind.SHORT) {
                findings.add(Finding.error(
                        DiagnosticCode.INVALID_UNION_ID,
                        element,
                        "@ThriftUnionId member in union '" + model.displayName()
                                + "' must use primitive short for Swift's default compiler codec, "
                                + "but found '" + type + "'."));
            }
        }
    }

    private void validateUnionConstruction(
            SwiftModel model,
            ResolvedLogicalFields resolvedFields,
            List<Finding> findings) {
        boolean hasZeroArgumentConstruction = false;
        Set<String> knownConstructors = new LinkedHashSet<String>();
        for (ExecutableElement executable : model.constructionExecutables()) {
            knownConstructors.add(ElementNames.qualifiedMemberName(executable));
            if (executable.getParameters().isEmpty()) {
                hasZeroArgumentConstruction = true;
            }
        }

        for (ResolvedLogicalFields.LogicalField field : resolvedFields.fields()) {
            Map<String, FieldPart> constructorPaths =
                    new LinkedHashMap<String, FieldPart>();
            for (FieldPart part : field.parts()) {
                Element declaration = part.declaration();
                if (part.source() != FieldPart.Source.CONSTRUCTOR_PARAMETER
                        || !(declaration instanceof ExecutableElement)
                        || declaration.getKind() != ElementKind.CONSTRUCTOR) {
                    continue;
                }
                String constructor = ElementNames.qualifiedMemberName(declaration);
                if (knownConstructors.contains(constructor)) {
                    constructorPaths.put(constructor, part);
                }
            }
            if (constructorPaths.size() > 1) {
                FieldPart target = new ArrayList<FieldPart>(constructorPaths.values()).get(1);
                findings.add(Finding.error(
                        DiagnosticCode.INVALID_UNION_CONSTRUCTOR,
                        target.element(),
                        "Thrift union '" + model.displayName() + "' field '"
                                + field.displayName() + "' is mapped to multiple one-argument "
                                + "@ThriftConstructor paths; Swift's selected constructor would "
                                + "depend on reflection order."));
            }
            else if (!hasZeroArgumentConstruction && constructorPaths.isEmpty()) {
                findings.add(Finding.error(
                        DiagnosticCode.INVALID_UNION_CONSTRUCTOR,
                        field.firstPart().element(),
                        "Thrift union '" + model.displayName() + "' field '"
                                + field.displayName() + "' has no one-argument "
                                + "@ThriftConstructor, and the construction type has no active "
                                + "zero-argument constructor path for decoding this variant."));
            }
        }
    }

    private void validateUnionPayloadIds(
            SwiftModel model,
            ResolvedLogicalFields resolvedFields,
            List<Finding> findings) {
        ResolvedLogicalFields.IdResolution resolution = resolvedFields.idResolution();
        for (ResolvedLogicalFields.LogicalField field : resolvedFields.fields()) {
            if (!field.ids(resolution).contains((short) 0)) {
                continue;
            }
            FieldPart target = field.lastPartWithId();
            findings.add(Finding.error(
                    DiagnosticCode.UNSAFE_UNION_FIELD_ID,
                    target.element(),
                    target.thriftField().annotation(),
                    target.thriftField().idSource(),
                    "Thrift union '" + model.displayName() + "' field '"
                            + field.displayName() + "' uses ID 0, which collides with the default "
                            + "compiler codec's initial no-field discriminator during decoding."));
        }
    }

    private TypeMirror resolvedUnionIdType(DeclaredType owner, Element member) {
        TypeMirror memberType = member.asType();
        try {
            memberType = types.asMemberOf(owner, member);
        }
        catch (IllegalArgumentException ignored) {
            // javac reports malformed hierarchies; fall back to the declared member type.
        }
        if (member instanceof ExecutableElement && memberType instanceof ExecutableType) {
            return ((ExecutableType) memberType).getReturnType();
        }
        return member instanceof VariableElement ? memberType : null;
    }
}
