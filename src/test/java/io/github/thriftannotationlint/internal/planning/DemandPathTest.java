package io.github.thriftannotationlint.internal.planning;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DemandPathTest {
    @Test
    void appendSharesTheCompleteParentPathWithoutCopyingIt() {
        ExactInstance first = new ExactInstance("A", 1);
        ExactInstance second = new ExactInstance("B", 2);
        ExactInstance third = new ExactInstance("A<String>", 3);
        DemandPath initial = DemandPath.initial("A", first);
        DemandPath middle = initial.append("B", second);
        DemandPath path = middle.append("A", third);

        assertSame(middle, path.parent());
        assertSame(initial, middle.parent());
        assertEquals(Arrays.asList(first, third), path.instances("A"));
        assertEquals(Arrays.asList(second), path.instances("B"));
    }
}
