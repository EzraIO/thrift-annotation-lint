package io.github.thriftannotationlint.internal.validation;

import io.github.thriftannotationlint.internal.diagnostic.DiagnosticCode;
import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.model.FieldPart;
import io.github.thriftannotationlint.internal.model.ResolvedLogicalFields;
import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.types.ThriftTypeInspector;

import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Validates Java types and their runtime codec carrier shapes. */
final class LogicalFieldTypeRules {
    void validate(
            LogicalFieldValidationContext context,
            ResolvedLogicalFields.LogicalField field,
            List<Finding> findings) {
        validateFieldTypes(context.model(), field, context.typeInspector(), findings);
        validateCanonicalCodecTypes(context.model(), field, context.typeInspector(), findings);
    }

    private void validateFieldTypes(
            SwiftModel model,
            ResolvedLogicalFields.LogicalField field,
            ThriftTypeInspector typeInspector,
            List<Finding> findings) {
        List<String> normalizedTypes = new ArrayList<String>();
        List<String> carrierShapes = new ArrayList<String>();
        Set<String> distinctTypes = new LinkedHashSet<String>();
        for (FieldPart part : field.parts()) {
            TypeMirror type = part.javaType();
            if (type.getKind() == TypeKind.ERROR) {
                continue;
            }
            String normalizedType = typeInspector.normalizedType(
                    type, field.isRecursiveReference(), model.dialect());
            distinctTypes.add(type.toString());
            if (normalizedType == null || !typeInspector.isSupported(type, model.dialect())) {
                findings.add(Finding.error(
                        DiagnosticCode.UNSUPPORTED_JAVA_TYPE,
                        part.element(),
                        ValidationText.modelField(model.displayName(), field.displayName())
                                + " uses unsupported Java type '" + type + "'."));
                break;
            }
            String carrierShape = typeInspector.carrierShape(type, model.dialect());
            if (!isCompatible(
                    normalizedTypes, carrierShapes, normalizedType, carrierShape, typeInspector)) {
                findings.add(Finding.error(
                        DiagnosticCode.CONFLICTING_JAVA_TYPES,
                        part.element(),
                        ValidationText.modelField(model.displayName(), field.displayName())
                                + " declares inconsistent Java types "
                                + distinctTypes + "."));
                break;
            }
            normalizedTypes.add(normalizedType);
            carrierShapes.add(carrierShape);
        }
    }

    private boolean isCompatible(
            List<String> normalizedTypes,
            List<String> carrierShapes,
            String normalizedType,
            String carrierShape,
            ThriftTypeInspector typeInspector) {
        for (int index = 0; index < normalizedTypes.size(); index++) {
            if (!typeInspector.areCompatibleNormalizedTypes(
                    normalizedTypes.get(index), normalizedType)
                    || !typeInspector.areCompatibleCarrierShapes(
                            carrierShapes.get(index), carrierShape)) {
                return false;
            }
        }
        return true;
    }

    private void validateCanonicalCodecTypes(
            SwiftModel model,
            ResolvedLogicalFields.LogicalField field,
            ThriftTypeInspector typeInspector,
            List<Finding> findings) {
        for (FieldPart part : field.parts()) {
            TypeMirror javaType = part.javaType();
            if (!typeInspector.isSupported(javaType, model.dialect())) {
                continue;
            }
            boolean unsafeRead = part.isReadable()
                    && !typeInspector.providesCanonicalValue(javaType, model.dialect());
            boolean unsafeWrite = part.isWritable()
                    && !typeInspector.acceptsDecodedValue(javaType, model.dialect());
            if (!unsafeRead && !unsafeWrite) {
                continue;
            }
            String direction = unsafeRead && unsafeWrite
                    ? "read/write"
                    : (unsafeRead ? "read" : "write/injection");
            findings.add(Finding.error(
                    DiagnosticCode.CONFLICTING_JAVA_TYPES,
                    part.element(),
                    ValidationText.modelField(model.displayName(), field.displayName())
                            + " uses Java type '" + javaType
                            + "' on a " + direction + " path that is incompatible with "
                            + model.dialect().runtimeName() + "'s "
                            + "canonical '" + typeInspector.canonicalDecodedTypeName(
                                    javaType, model.dialect())
                            + "' codec shape. Use compatible container types at every nesting "
                            + "level."));
        }
    }
}
