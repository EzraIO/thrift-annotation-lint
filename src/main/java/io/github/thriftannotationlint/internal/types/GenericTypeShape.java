package io.github.thriftannotationlint.internal.types;

/** Named generic arities and positions shared by wire-container analysis. */
final class GenericTypeShape {
    static final int VALUE_ARGUMENT_COUNT = 1;
    static final int MAP_ARGUMENT_COUNT = 2;
    static final int VALUE_ARGUMENT_INDEX = 0;
    static final int MAP_KEY_ARGUMENT_INDEX = 0;
    static final int MAP_VALUE_ARGUMENT_INDEX = 1;

    private GenericTypeShape() {
    }
}
