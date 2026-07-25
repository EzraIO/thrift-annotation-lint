package io.github.thriftannotationlint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactModelBudgetTest {
    @Test
    void sourceRootsAreFreeAndDuplicateReferencesAreIdempotent() {
        ExactModelBudget budget = new ExactModelBudget(1);

        assertEquals(
                ExactModelBudget.Reservation.FREE_SOURCE,
                budget.reserveResolved("Source", true));
        assertEquals(
                ExactModelBudget.Reservation.RESERVED,
                budget.reserveResolved("Box<String>", false));
        assertEquals(
                ExactModelBudget.Reservation.ALREADY_RESERVED,
                budget.reserveResolved("Box<String>", false));
        assertEquals(1, budget.size());
    }

    @Test
    void releaseMakesCapacityReusable() {
        ExactModelBudget budget = new ExactModelBudget(1);
        budget.reserveResolved("Old", false);

        budget.release("Old");

        assertFalse(budget.contains("Old"));
        assertEquals(
                ExactModelBudget.Reservation.RESERVED,
                budget.reserveResolved("New", false));
        assertTrue(budget.contains("New"));
    }

    @Test
    void reportsOnlyTheFirstLimitOverflow() {
        ExactModelBudget budget = new ExactModelBudget(1);
        budget.reserveResolved("First", false);

        assertEquals(
                ExactModelBudget.Reservation.EXCEEDED_FIRST,
                budget.reserveResolved("Second", false));
        assertEquals(
                ExactModelBudget.Reservation.EXCEEDED_REPEAT,
                budget.reserveResolved("Third", false));
    }
}
