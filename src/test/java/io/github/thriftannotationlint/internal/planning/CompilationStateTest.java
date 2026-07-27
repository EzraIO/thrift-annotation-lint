package io.github.thriftannotationlint.internal.planning;

import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.model.FieldPart;
import io.github.thriftannotationlint.internal.model.ResolvedLogicalFields;
import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.validation.ModelValidation;

import org.junit.jupiter.api.Test;

import javax.lang.model.element.Element;
import java.lang.reflect.Proxy;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilationStateTest {
    @Test
    void snapshotsPendingModelsBeforeTheNextRoundAggregatesThem() {
        CompilationState state = new CompilationState(4);

        CompilationState.RoundStart first = state.beginActiveRound();
        state.beginPendingAggregation();
        state.markPending("example.Box", SwiftModel.Kind.STRUCT);
        CompilationState.RoundStart second = state.beginActiveRound();

        assertFalse(first.rebuildDemandClosure());
        assertTrue(second.rebuildDemandClosure());
        assertEquals(
                SwiftModel.Kind.STRUCT,
                second.previousPendingModels().get("example.Box"));
    }

    @Test
    void processedIdentityCanBeForcedButOtherwiseRunsOnce() {
        CompilationState state = new CompilationState(4);

        assertTrue(state.beginModelValidation("example.Value", false));
        assertFalse(state.beginModelValidation("example.Value", false));
        assertTrue(state.beginModelValidation("example.Value", true));
    }

    @Test
    void resolvedModelsNeverCarryTypeMirrorsIntoTheNextRound() {
        CompilationState state = new CompilationState(4);
        state.beginActiveRound();
        SwiftModel model = new SwiftModel(
                SwiftModel.Kind.STRUCT,
                null,
                null,
                "example.Value",
                null,
                Collections.<FieldPart>emptyList(),
                Collections.emptyList(),
                Collections.<SwiftModel.ElementWithAnnotation>emptyList(),
                Collections.emptyList());
        state.storeResolvedModel(model);
        state.storeValidationResult(new ModelValidation(
                model,
                new ResolvedLogicalFields(
                        Collections.<ResolvedLogicalFields.LogicalField>emptyList(),
                        Collections.<FieldPart, Short>emptyMap()),
                Collections.<Finding>emptyList()));
        assertEquals(1, state.resolvedModels().size());
        assertEquals(1, state.validationResults().size());

        state.beginActiveRound();

        assertTrue(state.resolvedModels().isEmpty());
        assertTrue(state.validationResults().isEmpty());
    }

    @Test
    void releaseAtomicallyClearsBudgetAndDependencyAnchor() {
        CompilationState state = new CompilationState(1);
        Element anchor = (Element) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Element.class},
                (proxy, method, arguments) -> null);
        state.putDependencyAnchorIfAbsent("Old", anchor);
        assertEquals(
                ExactModelBudget.Reservation.RESERVED,
                state.reserveResolvedExactModel("Old"));
        assertSame(anchor, state.dependencyAnchor("Old"));

        state.releaseReferencedIdentity("Old");

        assertNull(state.dependencyAnchor("Old"));
        assertEquals(
                ExactModelBudget.Reservation.RESERVED,
                state.reserveResolvedExactModel("New"));
    }

    @Test
    void modelToContainerMigrationClearsEveryModelOwnedStateAtomically() {
        CompilationState state = new CompilationState(1);
        String identity = "example.Box<java.lang.String>";
        Element anchor = (Element) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Element.class},
                (proxy, method, arguments) -> null);
        state.putDependencyAnchorIfAbsent(identity, anchor);
        assertEquals(
                ExactModelBudget.Reservation.RESERVED,
                state.reserveResolvedExactModel(identity));
        assertTrue(state.beginModelValidation(identity, false));
        SwiftModel model = new SwiftModel(
                SwiftModel.Kind.STRUCT,
                null,
                null,
                identity,
                null,
                Collections.<FieldPart>emptyList(),
                Collections.emptyList(),
                Collections.<SwiftModel.ElementWithAnnotation>emptyList(),
                Collections.emptyList());
        state.storeResolvedModel(model);
        state.storeValidationResult(new ModelValidation(
                model,
                new ResolvedLogicalFields(
                        Collections.<ResolvedLogicalFields.LogicalField>emptyList(),
                        Collections.<FieldPart, Short>emptyMap()),
                Collections.<Finding>emptyList()));
        state.registerSourceModel(
                "example.Box",
                SwiftModel.Kind.STRUCT,
                identity);

        assertTrue(state.migrateSourceModelToContainer("example.Box", identity));

        assertTrue(state.historicalSourceContainers().contains("example.Box"));
        assertTrue(state.resolvedModels().isEmpty());
        assertTrue(state.validationResults().isEmpty());
        assertNull(state.dependencyAnchor(identity));
        assertTrue(state.beginModelValidation(identity, false));
        assertEquals(
                ExactModelBudget.Reservation.RESERVED,
                state.reserveResolvedExactModel("example.Other"));
    }
}
