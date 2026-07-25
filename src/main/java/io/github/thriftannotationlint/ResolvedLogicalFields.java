package io.github.thriftannotationlint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable result of resolving field identities and Swift's inferred field IDs. */
final class ResolvedLogicalFields {
    private final List<LogicalField> fields;
    private final IdResolution idResolution;

    ResolvedLogicalFields(List<LogicalField> fields, Map<FieldPart, Short> resolvedIds) {
        this.fields = Collections.unmodifiableList(new ArrayList<LogicalField>(fields));
        this.idResolution = new IdResolution(resolvedIds);
    }

    List<LogicalField> fields() {
        return fields;
    }

    IdResolution idResolution() {
        return idResolution;
    }

    static final class LogicalField {
        private final List<FieldPart> parts;

        LogicalField(List<FieldPart> parts) {
            List<FieldPart> sortedParts = new ArrayList<FieldPart>(parts);
            Collections.sort(sortedParts, new Comparator<FieldPart>() {
                @Override
                public int compare(FieldPart left, FieldPart right) {
                    return left.sortKey().compareTo(right.sortKey());
                }
            });
            this.parts = Collections.unmodifiableList(sortedParts);
        }

        List<FieldPart> parts() {
            return parts;
        }

        FieldPart firstPart() {
            return parts.get(0);
        }

        FieldPart lastPart() {
            return parts.get(parts.size() - 1);
        }

        FieldPart lastPartWithId() {
            for (int index = parts.size() - 1; index >= 0; index--) {
                if (parts.get(index).thriftField().id() != null) {
                    return parts.get(index);
                }
            }
            return lastPart();
        }

        FieldPart firstPartWithIdlAnnotations() {
            for (FieldPart part : parts) {
                if (!part.thriftField().idlAnnotations().values().isEmpty()) {
                    return part;
                }
            }
            return firstPart();
        }

        FieldPart firstPartWithExplicitRequiredness() {
            for (FieldPart part : parts) {
                if (!"UNSPECIFIED".equals(part.thriftField().requiredness())) {
                    return part;
                }
            }
            return lastPart();
        }

        Set<Short> ids(IdResolution resolution) {
            Set<Short> ids = new LinkedHashSet<Short>();
            for (FieldPart part : parts) {
                Short id = resolution.id(part);
                if (id != null) {
                    ids.add(id);
                }
            }
            return ids;
        }

        boolean hasUnresolvedPart(IdResolution resolution) {
            return firstUnresolvedPart(resolution) != null;
        }

        FieldPart firstUnresolvedPart(IdResolution resolution) {
            for (FieldPart part : parts) {
                if (resolution.id(part) == null) {
                    return part;
                }
            }
            return null;
        }

        Set<String> explicitRequirednessValues() {
            Set<String> values = new LinkedHashSet<String>();
            for (FieldPart part : parts) {
                String requiredness = part.thriftField().requiredness();
                if (!"UNSPECIFIED".equals(requiredness)) {
                    values.add(requiredness);
                }
            }
            return values;
        }

        boolean isRecursiveReference() {
            for (FieldPart part : parts) {
                if (Boolean.TRUE.equals(part.thriftField().recursive())) {
                    return true;
                }
            }
            return false;
        }

        boolean hasUnreliableIdentity() {
            for (FieldPart part : parts) {
                if (!part.isLogicalIdentityReliable()) {
                    return true;
                }
            }
            return false;
        }

        String displayName() {
            for (FieldPart part : parts) {
                if (part.thriftField().explicitName() != null) {
                    return part.thriftField().explicitName();
                }
            }
            return firstPart().displayName();
        }

        String sortKey() {
            return displayName() + "\u0000" + firstPart().sortKey();
        }
    }

    static final class IdResolution {
        private final Map<FieldPart, Short> ids;

        private IdResolution(Map<FieldPart, Short> ids) {
            this.ids = Collections.unmodifiableMap(
                    new LinkedHashMap<FieldPart, Short>(ids));
        }

        Short id(FieldPart part) {
            return ids.get(part);
        }
    }
}
