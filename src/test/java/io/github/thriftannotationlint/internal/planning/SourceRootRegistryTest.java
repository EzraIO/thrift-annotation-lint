package io.github.thriftannotationlint.internal.planning;

import io.github.thriftannotationlint.internal.model.SwiftModel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceRootRegistryTest {
    @Test
    void rejectsBypassingTheAtomicModelToContainerMigration() {
        SourceRootRegistry registry = new SourceRootRegistry();
        registry.registerModel("example.Root", SwiftModel.Kind.STRUCT, "example.Root");

        assertThrows(
                IllegalStateException.class,
                () -> registry.registerContainer("example.Root"));

        assertTrue(registry.isModelName("example.Root"));
        assertTrue(registry.isModelIdentity("example.Root"));
        assertFalse(registry.historicalContainers().contains("example.Root"));
    }

    @Test
    void migratesTheRegisteredSourceNameWithoutLeavingAnIdentity() {
        SourceRootRegistry registry = new SourceRootRegistry();
        registry.registerModel(
                "example.Box",
                SwiftModel.Kind.STRUCT,
                "example.Box<java.lang.String>");

        assertTrue(registry.migrateModelToContainer("example.Box"));
        assertFalse(registry.isModelIdentity("example.Box<java.lang.String>"));
        assertTrue(registry.historicalContainers().contains("example.Box"));
    }

    @Test
    void clearReturnsTheRegistryToUnknownState() {
        SourceRootRegistry registry = new SourceRootRegistry();
        registry.registerContainer("example.Root");

        registry.clear();

        assertTrue(registry.isEmpty());
    }
}
