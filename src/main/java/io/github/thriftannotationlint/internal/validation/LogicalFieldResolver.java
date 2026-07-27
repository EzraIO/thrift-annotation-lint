package io.github.thriftannotationlint.internal.validation;

import io.github.thriftannotationlint.internal.model.FieldPart;
import io.github.thriftannotationlint.internal.model.ResolvedLogicalFields;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reproduces Swift's logical-field merging and exactly-two-pass ID inference. */
final class LogicalFieldResolver {
    ResolvedLogicalFields resolve(List<FieldPart> parts) {
        Map<FieldPart, Short> resolvedIds = resolveFieldIds(parts);
        return new ResolvedLogicalFields(mergeLogicalFields(parts), resolvedIds);
    }

    private Map<FieldPart, Short> resolveFieldIds(List<FieldPart> parts) {
        Map<FieldPart, Short> resolved = new LinkedHashMap<FieldPart, Short>();
        for (FieldPart part : parts) {
            if (part.thriftField().id() != null) {
                resolved.put(part, part.thriftField().id());
            }
        }

        // Swift intentionally performs exactly these two passes. This is not a fixed-point
        // operation: an ID learned during the extracted-name pass must not flow back through an
        // explicit-name group processed in the first pass.
        propagateIdsByName(parts, resolved, true);
        propagateIdsByName(parts, resolved, false);
        return resolved;
    }

    private void propagateIdsByName(
            List<FieldPart> parts,
            Map<FieldPart, Short> resolved,
            boolean useExplicitName) {
        Map<String, List<FieldPart>> byName = new LinkedHashMap<String, List<FieldPart>>();
        for (FieldPart part : parts) {
            String name = useExplicitName
                    ? part.explicitOrExtractedName()
                    : part.extractedName();
            if (!part.isNameReliable(useExplicitName) || name == null) {
                continue;
            }
            List<FieldPart> group = byName.get(name);
            if (group == null) {
                group = new ArrayList<FieldPart>();
                byName.put(name, group);
            }
            group.add(part);
        }

        for (List<FieldPart> group : byName.values()) {
            if (group.size() <= 1) {
                continue;
            }
            Set<Short> ids = new LinkedHashSet<Short>();
            for (FieldPart part : group) {
                Short id = resolved.get(part);
                if (id != null) {
                    ids.add(id);
                }
            }
            if (ids.size() == 1) {
                Short id = ids.iterator().next();
                for (FieldPart part : group) {
                    resolved.put(part, id);
                }
            }
        }
    }

    private List<ResolvedLogicalFields.LogicalField> mergeLogicalFields(
            List<FieldPart> parts) {
        if (parts.isEmpty()) {
            return Collections.emptyList();
        }
        DisjointSet disjointSet = new DisjointSet(parts.size());
        mergeByName(parts, disjointSet, true);
        mergeByName(parts, disjointSet, false);
        mergeIdBasedParameterPaths(parts, disjointSet);

        Map<Integer, List<FieldPart>> groups = new LinkedHashMap<Integer, List<FieldPart>>();
        for (int index = 0; index < parts.size(); index++) {
            int root = disjointSet.find(index);
            List<FieldPart> group = groups.get(root);
            if (group == null) {
                group = new ArrayList<FieldPart>();
                groups.put(root, group);
            }
            group.add(parts.get(index));
        }

        List<ResolvedLogicalFields.LogicalField> fields =
                new ArrayList<ResolvedLogicalFields.LogicalField>();
        for (List<FieldPart> group : groups.values()) {
            fields.add(new ResolvedLogicalFields.LogicalField(group));
        }
        Collections.sort(fields, new Comparator<ResolvedLogicalFields.LogicalField>() {
            @Override
            public int compare(
                    ResolvedLogicalFields.LogicalField left,
                    ResolvedLogicalFields.LogicalField right) {
                return left.sortKey().compareTo(right.sortKey());
            }
        });
        return fields;
    }

    private void mergeIdBasedParameterPaths(
            List<FieldPart> parts,
            DisjointSet disjointSet) {
        Map<Short, List<Integer>> idBasedById =
                new LinkedHashMap<Short, List<Integer>>();
        Map<Short, Set<Integer>> reliableRootsById =
                new LinkedHashMap<Short, Set<Integer>>();
        for (int index = 0; index < parts.size(); index++) {
            FieldPart part = parts.get(index);
            Short id = part.thriftField().id();
            if (id == null) {
                continue;
            }
            if (part.requiresIdBasedMerge()) {
                List<Integer> indices = idBasedById.get(id);
                if (indices == null) {
                    indices = new ArrayList<Integer>();
                    idBasedById.put(id, indices);
                }
                indices.add(index);
            }
            else if (part.isLogicalNameReliable()) {
                Set<Integer> roots = reliableRootsById.get(id);
                if (roots == null) {
                    roots = new LinkedHashSet<Integer>();
                    reliableRootsById.put(id, roots);
                }
                roots.add(disjointSet.find(index));
            }
        }

        for (Map.Entry<Short, List<Integer>> entry : idBasedById.entrySet()) {
            List<Integer> idBased = entry.getValue();
            Set<Integer> reliableRoots = reliableRootsById.get(entry.getKey());
            if (reliableRoots == null || reliableRoots.isEmpty()) {
                int first = idBased.get(0);
                for (int position = 1; position < idBased.size(); position++) {
                    disjointSet.union(first, idBased.get(position));
                }
            }
            else if (reliableRoots.size() == 1) {
                int reliable = reliableRoots.iterator().next();
                for (Integer index : idBased) {
                    disjointSet.union(reliable, index);
                }
            }
            // Multiple reliable logical fields already use this ID. Keeping them separate lets
            // AW2002 report that ambiguity; a fallback-named parameter must not collapse them into
            // one field merely because it carries the same explicit ID.
        }
    }

    private void mergeByName(
            List<FieldPart> parts,
            DisjointSet disjointSet,
            boolean useExplicitName) {
        Map<String, Integer> firstByName = new HashMap<String, Integer>();
        for (int index = 0; index < parts.size(); index++) {
            FieldPart part = parts.get(index);
            String name = useExplicitName
                    ? part.explicitOrExtractedName()
                    : part.extractedName();
            if (!part.isNameReliable(useExplicitName) || name == null) {
                continue;
            }
            Integer first = firstByName.get(name);
            if (first == null) {
                firstByName.put(name, index);
            }
            else {
                disjointSet.union(first, index);
            }
        }
    }

    private static final class DisjointSet {
        private final int[] parent;
        private final byte[] rank;

        private DisjointSet(int size) {
            this.parent = new int[size];
            this.rank = new byte[size];
            for (int index = 0; index < size; index++) {
                parent[index] = index;
            }
        }

        int find(int value) {
            if (parent[value] != value) {
                parent[value] = find(parent[value]);
            }
            return parent[value];
        }

        void union(int left, int right) {
            int leftRoot = find(left);
            int rightRoot = find(right);
            if (leftRoot == rightRoot) {
                return;
            }
            if (rank[leftRoot] < rank[rightRoot]) {
                parent[leftRoot] = rightRoot;
            }
            else if (rank[leftRoot] > rank[rightRoot]) {
                parent[rightRoot] = leftRoot;
            }
            else {
                parent[rightRoot] = leftRoot;
                rank[leftRoot]++;
            }
        }
    }
}
