package io.github.thriftannotationlint.internal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable result of resolving field identities and Swift's inferred field IDs. */
public final class ResolvedLogicalFields {
    private final List<LogicalField> fields;
    private final IdResolution idResolution;

    public ResolvedLogicalFields(List<LogicalField> fields, Map<FieldPart, Short> resolvedIds) {
        this.fields = Collections.unmodifiableList(new ArrayList<LogicalField>(fields));
        this.idResolution = new IdResolution(resolvedIds);
    }

    public List<LogicalField> fields() {
        return fields;
    }

    public IdResolution idResolution() {
        return idResolution;
    }

    public static final class LogicalField {
        private final List<FieldPart> parts;

        public LogicalField(List<FieldPart> parts) {
            List<FieldPart> sortedParts = new ArrayList<FieldPart>(parts);
            Collections.sort(sortedParts, new Comparator<FieldPart>() {
                @Override
                public int compare(FieldPart left, FieldPart right) {
                    return left.sortKey().compareTo(right.sortKey());
                }
            });
            this.parts = Collections.unmodifiableList(sortedParts);
        }

        public List<FieldPart> parts() {
            return parts;
        }

        public FieldPart firstPart() {
            return parts.get(0);
        }

        public FieldPart lastPart() {
            return parts.get(parts.size() - 1);
        }

        public FieldPart lastPartWithId() {
            for (int index = parts.size() - 1; index >= 0; index--) {
                if (parts.get(index).thriftField().id() != null) {
                    return parts.get(index);
                }
            }
            return lastPart();
        }

        public FieldPart firstPartWithIdlAnnotations() {
            for (FieldPart part : parts) {
                if (!part.thriftField().idlAnnotations().values().isEmpty()) {
                    return part;
                }
            }
            return firstPart();
        }

        public FieldPart firstPartWithExplicitRequiredness() {
            for (FieldPart part : parts) {
                if (!"UNSPECIFIED".equals(part.thriftField().requiredness())) {
                    return part;
                }
            }
            return lastPart();
        }

        public Set<Short> ids(IdResolution resolution) {
            Set<Short> ids = new LinkedHashSet<Short>();
            for (FieldPart part : parts) {
                Short id = resolution.id(part);
                if (id != null) {
                    ids.add(id);
                }
            }
            return ids;
        }

        public boolean hasUnresolvedPart(IdResolution resolution) {
            return firstUnresolvedPart(resolution) != null;
        }

        public FieldPart firstUnresolvedPart(IdResolution resolution) {
            for (FieldPart part : parts) {
                if (resolution.id(part) == null) {
                    return part;
                }
            }
            return null;
        }

        public Set<String> explicitRequirednessValues() {
            Set<String> values = new LinkedHashSet<String>();
            for (FieldPart part : parts) {
                String requiredness = part.thriftField().requiredness();
                if (!"UNSPECIFIED".equals(requiredness)) {
                    values.add(requiredness);
                }
            }
            return values;
        }

        public boolean isRecursiveReference() {
            for (FieldPart part : parts) {
                if (Boolean.TRUE.equals(part.thriftField().recursive())) {
                    return true;
                }
            }
            return false;
        }

        public boolean hasUnreliableIdentity() {
            for (FieldPart part : parts) {
                if (!part.isLogicalIdentityReliable()) {
                    return true;
                }
            }
            return false;
        }

        public String displayName() {
            for (FieldPart part : parts) {
                if (part.thriftField().explicitName() != null) {
                    return part.thriftField().explicitName();
                }
            }
            return firstPart().displayName();
        }

        public String sortKey() {
            return displayName() + "\u0000" + firstPart().sortKey();
        }
    }

    public static final class IdResolution {
        private final Map<FieldPart, Short> ids;

        private IdResolution(Map<FieldPart, Short> ids) {
            this.ids = Collections.unmodifiableMap(
                    new LinkedHashMap<FieldPart, Short>(ids));
        }

        public Short id(FieldPart part) {
            return ids.get(part);
        }
    }
}
