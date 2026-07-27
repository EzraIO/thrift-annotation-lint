package io.github.thriftannotationlint.internal.extract;

/** Optional package-private counters used by deterministic performance tests. */
final class MemberResolutionMetrics {
    private int memberEnumerations;

    void memberEnumeration() {
        memberEnumerations++;
    }

    int memberEnumerations() {
        return memberEnumerations;
    }

    void reset() {
        memberEnumerations = 0;
    }
}
