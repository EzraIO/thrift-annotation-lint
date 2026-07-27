package io.github.thriftannotationlint.internal.model;


import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;

public final class FieldPart {
    public enum Source {
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

    public FieldPart(
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

    public FieldPart(
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

    public FieldPart(
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

    public FieldPart(
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

    public Source source() {
        return source;
    }

    public Element element() {
        return element;
    }

    public Element declaration() {
        return declaration;
    }

    public String extractedName() {
        return extractedName;
    }

    public String explicitOrExtractedName() {
        return thriftField.explicitName() == null
                ? extractedName
                : thriftField.explicitName();
    }

    public TypeMirror javaType() {
        return javaType;
    }

    public ThriftFieldData thriftField() {
        return thriftField;
    }

    public boolean isReadable() {
        return readable;
    }

    public boolean isWritable() {
        return writable;
    }

    public boolean isExtractedNameReliable() {
        return extractedNameReliable;
    }

    public boolean isNameReliable(boolean useExplicitName) {
        return useExplicitName && thriftField.explicitName() != null
                || extractedNameReliable;
    }

    public boolean isLogicalNameReliable() {
        return thriftField.explicitName() != null || extractedNameReliable;
    }

    boolean isLogicalIdentityReliable() {
        return thriftField.id() != null || isLogicalNameReliable();
    }

    public boolean requiresIdBasedMerge() {
        return thriftField.id() != null
                && thriftField.explicitName() == null
                && idBasedMerge;
    }

    boolean hasNoLvtVariant() {
        return noLvtExtractedName != null
                && !noLvtExtractedName.equals(extractedName);
    }

    public FieldPart noLvtVariant() {
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

    public String displayName() {
        String name = explicitOrExtractedName();
        return name == null || name.isEmpty() ? "<unnamed>" : name;
    }

    public String sortKey() {
        return ElementNames.qualifiedMemberName(declaration)
                + "\u0000" + source
                + "\u0000" + displayName();
    }
}
