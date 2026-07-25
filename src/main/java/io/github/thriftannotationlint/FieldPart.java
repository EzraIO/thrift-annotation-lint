package io.github.thriftannotationlint;

import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;

final class FieldPart {
    enum Source {
        FIELD,
        GETTER,
        SETTER,
        CONSTRUCTOR_PARAMETER,
        METHOD_PARAMETER
    }

    private final Source source;
    private final Element element;
    private final Element declaration;
    private final String extractedName;
    private final TypeMirror javaType;
    private final ThriftFieldData thriftField;
    private final boolean readable;
    private final boolean writable;
    private final boolean extractedNameReliable;
    private final String noLvtExtractedName;
    private final boolean idBasedMerge;

    FieldPart(
            Source source,
            Element element,
            Element declaration,
            String extractedName,
            TypeMirror javaType,
            ThriftFieldData thriftField,
            boolean readable,
            boolean writable) {
        this(
                source,
                element,
                declaration,
                extractedName,
                javaType,
                thriftField,
                readable,
                writable,
                true,
                null);
    }

    FieldPart(
            Source source,
            Element element,
            Element declaration,
            String extractedName,
            TypeMirror javaType,
            ThriftFieldData thriftField,
            boolean readable,
            boolean writable,
            boolean extractedNameReliable) {
        this(
                source,
                element,
                declaration,
                extractedName,
                javaType,
                thriftField,
                readable,
                writable,
                extractedNameReliable,
                null);
    }

    FieldPart(
            Source source,
            Element element,
            Element declaration,
            String extractedName,
            TypeMirror javaType,
            ThriftFieldData thriftField,
            boolean readable,
            boolean writable,
            boolean extractedNameReliable,
            String noLvtExtractedName) {
        this(
                source,
                element,
                declaration,
                extractedName,
                javaType,
                thriftField,
                readable,
                writable,
                extractedNameReliable,
                noLvtExtractedName,
                !extractedNameReliable);
    }

    FieldPart(
            Source source,
            Element element,
            Element declaration,
            String extractedName,
            TypeMirror javaType,
            ThriftFieldData thriftField,
            boolean readable,
            boolean writable,
            boolean extractedNameReliable,
            String noLvtExtractedName,
            boolean idBasedMerge) {
        this.source = source;
        this.element = element;
        this.declaration = declaration;
        this.extractedName = extractedName;
        this.javaType = javaType;
        this.thriftField = thriftField;
        this.readable = readable;
        this.writable = writable;
        this.extractedNameReliable = extractedNameReliable;
        this.noLvtExtractedName = noLvtExtractedName;
        this.idBasedMerge = idBasedMerge;
    }

    Source source() {
        return source;
    }

    Element element() {
        return element;
    }

    Element declaration() {
        return declaration;
    }

    String extractedName() {
        return extractedName;
    }

    String explicitOrExtractedName() {
        return thriftField.explicitName() == null
                ? extractedName
                : thriftField.explicitName();
    }

    TypeMirror javaType() {
        return javaType;
    }

    ThriftFieldData thriftField() {
        return thriftField;
    }

    boolean isReadable() {
        return readable;
    }

    boolean isWritable() {
        return writable;
    }

    boolean isExtractedNameReliable() {
        return extractedNameReliable;
    }

    boolean isNameReliable(boolean useExplicitName) {
        return useExplicitName && thriftField.explicitName() != null
                || extractedNameReliable;
    }

    boolean isLogicalNameReliable() {
        return thriftField.explicitName() != null || extractedNameReliable;
    }

    boolean isLogicalIdentityReliable() {
        return thriftField.id() != null || isLogicalNameReliable();
    }

    boolean requiresIdBasedMerge() {
        return thriftField.id() != null
                && thriftField.explicitName() == null
                && idBasedMerge;
    }

    boolean hasNoLvtVariant() {
        return noLvtExtractedName != null
                && !noLvtExtractedName.equals(extractedName);
    }

    FieldPart noLvtVariant() {
        if (!hasNoLvtVariant()) {
            return this;
        }
        return new FieldPart(
                source,
                element,
                declaration,
                noLvtExtractedName,
                javaType,
                thriftField,
                readable,
                writable,
                true,
                null,
                true);
    }

    String displayName() {
        String name = explicitOrExtractedName();
        return name == null || name.isEmpty() ? "<unnamed>" : name;
    }

    String sortKey() {
        return ElementNames.qualifiedMemberName(declaration)
                + "\u0000" + source
                + "\u0000" + displayName();
    }
}
