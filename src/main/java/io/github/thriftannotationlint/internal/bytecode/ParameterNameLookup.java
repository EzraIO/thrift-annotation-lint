package io.github.thriftannotationlint.internal.bytecode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable result for one runtime's parameter-name view of one executable. */
final class ParameterNameLookup {
    private static final long BASE_WEIGHT = 48L;
    private static final long LIST_BASE_WEIGHT = 32L;
    private static final long REFERENCE_WEIGHT = 8L;
    private static final long STRING_BASE_WEIGHT = 40L;
    private static final long UTF16_BYTES_PER_CHARACTER = 2L;

    private final List<String> names;
    private final List<String> fallbackNames;
    private final String failure;

    private ParameterNameLookup(
            List<String> names,
            List<String> fallbackNames,
            String failure) {
        this.names = immutableCopy(names);
        this.fallbackNames = immutableCopy(fallbackNames);
        this.failure = failure;
    }

    static ParameterNameLookup found(List<String> names) {
        return new ParameterNameLookup(names, null, null);
    }

    static ParameterNameLookup absent() {
        return absent(null);
    }

    static ParameterNameLookup absent(List<String> fallbackNames) {
        return new ParameterNameLookup(null, fallbackNames, null);
    }

    static ParameterNameLookup invalid(String failure) {
        return new ParameterNameLookup(null, null, failure);
    }

    boolean isFound() {
        return names != null;
    }

    boolean isInvalid() {
        return failure != null;
    }

    List<String> names() {
        return mutableCopy(names);
    }

    List<String> fallbackNames() {
        return mutableCopy(fallbackNames);
    }

    String failure() {
        return failure;
    }

    long estimatedWeight() {
        return BASE_WEIGHT + stringWeight(failure)
                + listWeight(names) + listWeight(fallbackNames);
    }

    private static List<String> immutableCopy(List<String> values) {
        return values == null
                ? null
                : Collections.unmodifiableList(new ArrayList<String>(values));
    }

    private static List<String> mutableCopy(List<String> values) {
        return values == null ? null : new ArrayList<String>(values);
    }

    private static long listWeight(List<String> values) {
        if (values == null) {
            return 0;
        }
        long weight = LIST_BASE_WEIGHT + REFERENCE_WEIGHT * values.size();
        for (String value : values) {
            weight += stringWeight(value);
        }
        return weight;
    }

    private static long stringWeight(String value) {
        return value == null
                ? 0
                : STRING_BASE_WEIGHT + UTF16_BYTES_PER_CHARACTER * value.length();
    }
}
