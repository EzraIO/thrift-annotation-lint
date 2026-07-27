package io.github.thriftannotationlint.internal.planning;

/** One exact generic identity already present on a demand path. */
final class ExactInstance {
    final String identity;
    final int complexity;

    ExactInstance(String identity, int complexity) {
        this.identity = identity;
        this.complexity = complexity;
    }
}
