package io.github.thriftannotationlint.internal.planning;

import io.github.thriftannotationlint.internal.diagnostic.Finding;
import io.github.thriftannotationlint.internal.model.FieldPart;
import io.github.thriftannotationlint.internal.model.ResolvedLogicalFields;
import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;
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
        state.markPending("example.Box", SwiftModel.Kind.STRUCT,
                ThriftAnnotationDialect.FACEBOOK_SWIFT);
        state.markPending("example.Box", SwiftModel.Kind.STRUCT,
                ThriftAnnotationDialect.AIRLIFT_DRIFT);
        CompilationState.RoundStart second = state.beginActiveRound();

        assertFalse(first.rebuildDemandClosure());
        assertTrue(second.rebuildDemandClosure());
        assertEquals(
                SwiftModel.Kind.STRUCT,
                second.previousPendingModels().get(ModelRegistration.key(
                        "example.Box", ThriftAnnotationDialect.FACEBOOK_SWIFT)).kind);
        assertEquals(
                ThriftAnnotationDialect.AIRLIFT_DRIFT,
                second.previousPendingModels().get(ModelRegistration.key(
                        "example.Box", ThriftAnnotationDialect.AIRLIFT_DRIFT)).dialect);
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
                "FACEBOOK_SWIFT\0example.Value",
                ThriftAnnotationDialect.FACEBOOK_SWIFT,
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
        String cacheKey = "FACEBOOK_SWIFT\0" + identity;
        Element anchor = (Element) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Element.class},
                (proxy, method, arguments) -> null);
        state.putDependencyAnchorIfAbsent(cacheKey, anchor);
        assertEquals(
                ExactModelBudget.Reservation.RESERVED,
                state.reserveResolvedExactModel(cacheKey));
        assertTrue(state.beginModelValidation(cacheKey, false));
        SwiftModel model = new SwiftModel(
                SwiftModel.Kind.STRUCT,
                null,
                null,
                identity,
                cacheKey,
                ThriftAnnotationDialect.FACEBOOK_SWIFT,
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
                ThriftAnnotationDialect.FACEBOOK_SWIFT,
                cacheKey);

        assertTrue(state.migrateSourceModelToContainer(
                "example.Box", ThriftAnnotationDialect.FACEBOOK_SWIFT, cacheKey));

        assertTrue(state.historicalSourceContainers().containsKey("example.Box"));
        assertTrue(state.resolvedModels().isEmpty());
        assertTrue(state.validationResults().isEmpty());
        assertNull(state.dependencyAnchor(cacheKey));
        assertTrue(state.beginModelValidation(cacheKey, false));
        assertEquals(
                ExactModelBudget.Reservation.RESERVED,
                state.reserveResolvedExactModel("example.Other"));
    }
}
