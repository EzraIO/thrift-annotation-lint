package io.github.thriftannotationlint.internal.types;

/** Optional package-private counters used by deterministic performance tests. */
final class TypeInspectionMetrics {
    private int hierarchyLookups;
    private int classifications;

    void hierarchyLookup() {
        hierarchyLookups++;
    }

    void classification() {
        classifications++;
    }

    int hierarchyLookups() {
        return hierarchyLookups;
    }

    int classifications() {
        return classifications;
    }

    void reset() {
        hierarchyLookups = 0;
        classifications = 0;
    }
}
