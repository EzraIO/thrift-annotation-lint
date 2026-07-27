package io.github.thriftannotationlint.internal.validation;

import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.model.SwiftAnnotations;
import io.github.thriftannotationlint.internal.model.FieldPart;
import io.github.thriftannotationlint.internal.model.ResolvedLogicalFields;
import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.model.ThriftFieldData;
import io.github.thriftannotationlint.internal.model.ElementNames;
import io.github.thriftannotationlint.internal.types.SwiftTypeInspector;

import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Applies rules that operate on already-resolved logical fields. */
final class LogicalFieldValidator {
    private final SwiftTypeInspector typeInspector;

    LogicalFieldValidator(SwiftTypeInspector typeInspector) {
        this.typeInspector = typeInspector;
    }

    List<Finding> validate(SwiftModel model, ResolvedLogicalFields resolvedFields) {
        List<Finding> findings = new ArrayList<Finding>();
        ResolvedLogicalFields.IdResolution idResolution =
                resolvedFields.idResolution();
        Map<Short, List<ResolvedLogicalFields.LogicalField>> fieldsById =
                new LinkedHashMap<Short, List<ResolvedLogicalFields.LogicalField>>();

        for (ResolvedLogicalFields.LogicalField field : resolvedFields.fields()) {
            validateFieldAnnotationMaps(model, field, findings);
            validateFieldNames(model, field, findings);
            validateRequiredness(model, field, findings);
            validateLegacyId(model, field, idResolution, findings);
            validateRecursiveField(model, field, findings);
            validateFieldTypes(model, field, findings);
            validateCanonicalCodecTypes(model, field, findings);
            validateUnambiguousRuntimeSelection(model, field, findings);
            if (!field.hasUnreliableIdentity()) {
                validateAccessPaths(model, field, findings);
            }

            Set<Short> ids = field.ids(idResolution);
            if (ids.size() > 1) {
                FieldPart target = field.lastPartWithId();
                findings.add(Finding.error(
                        DiagnosticCode.CONFLICTING_FIELD_ID,
                        target.element(),
                        target.thriftField().annotation(),
                        target.thriftField().idSource(),
                        "Thrift model '" + model.displayName() + "' field '"
                                + field.displayName() + "' declares conflicting IDs " + ids + "."));
            }
            else if (field.hasUnresolvedPart(idResolution)) {
                FieldPart target = field.firstUnresolvedPart(idResolution);
                if (target.isLogicalNameReliable()) {
                    findings.add(Finding.error(
                            DiagnosticCode.MISSING_FIELD_ID,
                            target.element(),
                            "Thrift model '" + model.displayName() + "' field '"
                                    + field.displayName() + "' does not resolve a field ID after "
                                    + "Swift's two-phase name inference."));
                }
            }
            else {
                Short id = ids.iterator().next();
                if (!field.hasUnreliableIdentity()) {
                    List<ResolvedLogicalFields.LogicalField> sameId = fieldsById.get(id);
                    if (sameId == null) {
                        sameId = new ArrayList<ResolvedLogicalFields.LogicalField>();
                        fieldsById.put(id, sameId);
                    }
                    sameId.add(field);
                }
            }
        }

        validateDuplicateIds(model, fieldsById, findings);
        return findings;
    }

    private void validateFieldAnnotationMaps(
            SwiftModel model,
            ResolvedLogicalFields.LogicalField field,
            List<Finding> findings) {
        Set<Map<String, String>> nonEmptyMaps =
                new LinkedHashSet<Map<String, String>>();
        for (FieldPart part : field.parts()) {
            SwiftAnnotations.IdlAnnotations idl = part.thriftField().idlAnnotations();
            if (!idl.duplicateKeys().isEmpty()) {
                findings.add(Finding.error(
                        DiagnosticCode.CONFLICTING_IDL_ANNOTATIONS,
                        part.element(),
                        part.thriftField().annotation(),
                        idl.sourceValue(),
                        "Thrift model '" + model.displayName() + "' field '"
                                + field.displayName() + "' declares duplicate IDL annotation keys "
                                + idl.duplicateKeys() + "."));
            }
            if (!idl.values().isEmpty()) {
                nonEmptyMaps.add(idl.values());
            }
        }
        if (nonEmptyMaps.size() > 1) {
            FieldPart target = field.firstPartWithIdlAnnotations();
            findings.add(Finding.error(
                    DiagnosticCode.CONFLICTING_IDL_ANNOTATIONS,
                    target.element(),
                    target.thriftField().annotation(),
                    target.thriftField().idlAnnotations().sourceValue(),
                    "Thrift model '" + model.displayName() + "' field '"
                            + field.displayName() + "' declares conflicting IDL annotation maps."));
        }
    }

    private void validateFieldNames(
            SwiftModel model,
            ResolvedLogicalFields.LogicalField field,
            List<Finding> findings) {
        Set<String> names = new LinkedHashSet<String>();
        for (FieldPart part : field.parts()) {
            if (part.thriftField().explicitName() != null) {
                names.add(part.thriftField().explicitName());
            }
        }
        if (names.size() > 1) {
            findings.add(Finding.error(
                    DiagnosticCode.CONFLICTING_FIELD_NAME,
                    field.lastPart().element(),
                    "Thrift model '" + model.displayName() + "' field '"
                            + field.displayName() + "' declares multiple explicit names " + names + "."));
        }
    }

    private void validateRequiredness(
            SwiftModel model,
            ResolvedLogicalFields.LogicalField field,
            List<Finding> findings) {
        Set<String> values = field.explicitRequirednessValues();
        if (values.size() > 1) {
            FieldPart target = field.firstPartWithExplicitRequiredness();
            findings.add(Finding.error(
                    DiagnosticCode.CONFLICTING_REQUIREDNESS,
                    target.element(),
                    "Thrift model '" + model.displayName() + "' field '"
                            + field.displayName() + "' declares conflicting requiredness values "
                            + values + "."));
        }
        if (model.kind() == SwiftModel.Kind.UNION
                && (values.contains("REQUIRED") || values.contains("OPTIONAL"))) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_UNION_REQUIREDNESS,
                    field.lastPart().element(),
                    "Thrift union '" + model.displayName() + "' field '"
                            + field.displayName() + "' must not be marked required or optional."));
        }
    }

    private void validateLegacyId(
            SwiftModel model,
            ResolvedLogicalFields.LogicalField field,
            ResolvedLogicalFields.IdResolution idResolution,
            List<Finding> findings) {
        Set<Short> ids = field.ids(idResolution);
        if (ids.size() != 1) {
            return;
        }
        short id = ids.iterator().next();
        Set<Boolean> legacyValues = new LinkedHashSet<Boolean>();
        for (FieldPart part : field.parts()) {
            ThriftFieldData data = part.thriftField();
            // Swift cannot distinguish an omitted isLegacyId from an explicit false when a part
            // has no configured ID, so it deliberately treats that value as absent.
            if (data.id() != null || data.legacyId()) {
                legacyValues.add(Boolean.valueOf(data.legacyId()));
            }
        }
        if (legacyValues.size() > 1) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_LEGACY_ID,
                    field.lastPart().element(),
                    "Thrift model '" + model.displayName() + "' field '"
                            + field.displayName() + "' mixes isLegacyId=true and isLegacyId=false."));
        }
        if (id < 0 && !legacyValues.contains(Boolean.TRUE)) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_LEGACY_ID,
                    field.lastPartWithId().element(),
                    "Thrift model '" + model.displayName() + "' field '"
                            + field.displayName()
                            + "' has a negative ID and must set isLegacyId=true."));
        }
        else if (id >= 0 && legacyValues.contains(Boolean.TRUE)) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_LEGACY_ID,
                    field.lastPartWithId().element(),
                    "Thrift model '" + model.displayName() + "' field '"
                            + field.displayName()
                            + "' sets isLegacyId=true for a non-negative ID."));
        }
    }

    private void validateRecursiveField(
            SwiftModel model,
            ResolvedLogicalFields.LogicalField field,
            List<Finding> findings) {
        Set<Boolean> recursiveValues = new LinkedHashSet<Boolean>();
        for (FieldPart part : field.parts()) {
            if (part.thriftField().recursive() != null) {
                recursiveValues.add(part.thriftField().recursive());
            }
        }
        if (recursiveValues.size() > 1) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_RECURSIVE_FIELD,
                    field.lastPart().element(),
                    "Thrift model '" + model.displayName() + "' field '"
                            + field.displayName()
                            + "' declares conflicting recursive-reference settings."));
        }
        if (model.kind() == SwiftModel.Kind.STRUCT
                && recursiveValues.contains(Boolean.TRUE)
                && !field.explicitRequirednessValues().contains("OPTIONAL")) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_RECURSIVE_FIELD,
                    field.lastPart().element(),
                    "Recursive field '" + field.displayName() + "' in Thrift struct '"
                            + model.displayName() + "' must be marked optional."));
        }
    }

    private void validateFieldTypes(
            SwiftModel model,
            ResolvedLogicalFields.LogicalField field,
            List<Finding> findings) {
        List<String> normalizedTypes = new ArrayList<String>();
        Set<String> distinctTypes = new LinkedHashSet<String>();
        for (FieldPart part : field.parts()) {
            TypeMirror type = part.javaType();
            if (type.getKind() == TypeKind.ERROR) {
                continue;
            }
            String normalizedType = typeInspector.normalizedType(
                    type,
                    field.isRecursiveReference());
            distinctTypes.add(type.toString());
            if (normalizedType == null || !typeInspector.isSupported(type)) {
                findings.add(Finding.error(
                        DiagnosticCode.UNSUPPORTED_JAVA_TYPE,
                        part.element(),
                        "Thrift model '" + model.displayName() + "' field '"
                                + field.displayName() + "' uses unsupported Java type '" + type + "'."));
                break;
            }
            boolean compatible = true;
            for (String previousType : normalizedTypes) {
                if (!typeInspector.areCompatibleNormalizedTypes(previousType, normalizedType)) {
                    compatible = false;
                    break;
                }
            }
            if (!compatible) {
                findings.add(Finding.error(
                        DiagnosticCode.CONFLICTING_JAVA_TYPES,
                        part.element(),
                        "Thrift model '" + model.displayName() + "' field '"
                                + field.displayName() + "' declares inconsistent Java types "
                                + distinctTypes + "."));
                break;
            }
            normalizedTypes.add(normalizedType);
        }
    }

    private void validateCanonicalCodecTypes(
            SwiftModel model,
            ResolvedLogicalFields.LogicalField field,
            List<Finding> findings) {
        for (FieldPart part : field.parts()) {
            TypeMirror javaType = part.javaType();
            if (!typeInspector.isSupported(javaType)) {
                continue;
            }
            boolean unsafeRead = part.isReadable()
                    && !typeInspector.providesCanonicalValue(javaType);
            boolean unsafeWrite = part.isWritable()
                    && !typeInspector.acceptsDecodedValue(javaType);
            if (!unsafeRead && !unsafeWrite) {
                continue;
            }
            String direction = unsafeRead && unsafeWrite
                    ? "read/write"
                    : (unsafeRead ? "read" : "write/injection");
            findings.add(Finding.error(
                    DiagnosticCode.CONFLICTING_JAVA_TYPES,
                    part.element(),
                    "Thrift model '" + model.displayName() + "' field '"
                            + field.displayName() + "' uses Java type '" + javaType
                            + "' on a " + direction + " path that is incompatible with Swift's "
                            + "canonical '" + typeInspector.canonicalDecodedTypeName(javaType)
                            + "' codec shape. Use compatible container types at every nesting "
                            + "level."));
        }
    }

    private void validateAccessPaths(
            SwiftModel model,
            ResolvedLogicalFields.LogicalField field,
            List<Finding> findings) {
        boolean readable = false;
        boolean writable = false;
        for (FieldPart part : field.parts()) {
            readable |= part.isReadable();
            writable |= part.isWritable();
        }
        if (!readable || !writable) {
            String missing = !readable && !writable
                    ? "read and write"
                    : (!readable ? "read" : "write");
            findings.add(Finding.error(
                    DiagnosticCode.MISSING_ACCESS_PATH,
                    field.firstPart().element(),
                    "Thrift model '" + model.displayName() + "' field '"
                            + field.displayName() + "' does not have a valid " + missing + " path."));
        }
    }

    private void validateUnambiguousRuntimeSelection(
            SwiftModel model,
            ResolvedLogicalFields.LogicalField field,
            List<Finding> findings) {
        List<FieldPart> fields = new ArrayList<FieldPart>();
        List<FieldPart> getters = new ArrayList<FieldPart>();
        for (FieldPart part : field.parts()) {
            if (!part.isReadable()) {
                continue;
            }
            if (part.source() == FieldPart.Source.GETTER) {
                getters.add(part);
            }
            else if (part.source() == FieldPart.Source.FIELD) {
                fields.add(part);
            }
        }
        // Swift installs field extractors first and method extractors second. A unique getter
        // therefore deterministically replaces any number of same-field field extractors; only
        // multiple extractors in the final winning tier depend on reflection order.
        List<FieldPart> winningExtractors = getters.isEmpty() ? fields : getters;
        if (winningExtractors.size() > 1) {
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_METHOD_OR_CONSTRUCTOR,
                    winningExtractors.get(1).declaration(),
                    "Thrift model '" + model.displayName() + "' field '"
                            + field.displayName() + "' declares multiple "
                            + winningExtractors.get(0).source().name().toLowerCase(Locale.ROOT)
                            + " extraction paths; Swift retains only one, selected by "
                            + "unspecified reflection order."));
        }

        if (model.kind() != SwiftModel.Kind.UNION) {
            return;
        }
        Map<String, FieldPart> methodInjections = new LinkedHashMap<String, FieldPart>();
        for (FieldPart part : field.parts()) {
            if (!part.isWritable()
                    || (part.source() != FieldPart.Source.SETTER
                    && part.source() != FieldPart.Source.METHOD_PARAMETER)) {
                continue;
            }
            String declaration = ElementNames.qualifiedMemberName(part.declaration());
            if (!methodInjections.containsKey(declaration)) {
                methodInjections.put(declaration, part);
            }
        }
        if (methodInjections.size() > 1) {
            List<FieldPart> injections =
                    new ArrayList<FieldPart>(methodInjections.values());
            findings.add(Finding.error(
                    DiagnosticCode.INVALID_METHOD_OR_CONSTRUCTOR,
                    injections.get(1).declaration(),
                    "Thrift union '" + model.displayName() + "' field '"
                            + field.displayName() + "' declares multiple method injection paths; "
                            + "Swift retains only one, selected by unspecified reflection order."));
        }
    }

    private void validateDuplicateIds(
            SwiftModel model,
            Map<Short, List<ResolvedLogicalFields.LogicalField>> fieldsById,
            List<Finding> findings) {
        for (Map.Entry<Short, List<ResolvedLogicalFields.LogicalField>> entry
                : fieldsById.entrySet()) {
            List<ResolvedLogicalFields.LogicalField> fields = entry.getValue();
            if (fields.size() <= 1) {
                continue;
            }
            List<String> names = new ArrayList<String>();
            for (ResolvedLogicalFields.LogicalField field : fields) {
                names.add(field.displayName());
            }
            ResolvedLogicalFields.LogicalField targetField = fields.get(1);
            FieldPart target = targetField.lastPartWithId();
            findings.add(Finding.error(
                    DiagnosticCode.DUPLICATE_FIELD_ID,
                    target.element(),
                    target.thriftField().annotation(),
                    target.thriftField().idSource(),
                    "Thrift model '" + model.displayName() + "' uses field ID "
                            + entry.getKey() + " for different logical fields " + names + "."));
        }
    }
}
