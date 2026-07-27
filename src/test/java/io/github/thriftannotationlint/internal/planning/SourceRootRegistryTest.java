package io.github.thriftannotationlint.internal.planning;

import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceRootRegistryTest {
    @Test
    void rejectsBypassingTheAtomicModelToContainerMigration() {
        SourceRootRegistry registry = new SourceRootRegistry();
        registry.registerModel("example.Root", SwiftModel.Kind.STRUCT,
                ThriftAnnotationDialect.FACEBOOK_SWIFT, "example.Root");

        assertThrows(
                IllegalStateException.class,
                () -> registry.registerContainer(
                        "example.Root", ThriftAnnotationDialect.FACEBOOK_SWIFT));

        assertTrue(registry.isModelName("example.Root"));
        assertTrue(registry.isModelIdentity("example.Root"));
        assertFalse(registry.historicalContainers().containsKey("example.Root"));
    }

    @Test
    void migratesTheRegisteredSourceNameWithoutLeavingAnIdentity() {
        SourceRootRegistry registry = new SourceRootRegistry();
        registry.registerModel(
                "example.Box",
                SwiftModel.Kind.STRUCT,
                ThriftAnnotationDialect.FACEBOOK_SWIFT,
                "example.Box<java.lang.String>");

        assertTrue(registry.migrateModelToContainer(
                "example.Box", ThriftAnnotationDialect.FACEBOOK_SWIFT));
        assertFalse(registry.isModelIdentity("example.Box<java.lang.String>"));
        assertTrue(registry.historicalContainers().containsKey("example.Box"));
    }

    @Test
    void clearReturnsTheRegistryToUnknownState() {
        SourceRootRegistry registry = new SourceRootRegistry();
        registry.registerContainer("example.Root", ThriftAnnotationDialect.FACEBOOK_SWIFT);

        registry.clear();

        assertTrue(registry.isEmpty());
    }

    @Test
    void retainsIndependentDialectRegistrationsForOnePlainEnum() {
        SourceRootRegistry registry = new SourceRootRegistry();
        registry.registerModel(
                "example.SharedState",
                SwiftModel.Kind.ENUM,
                ThriftAnnotationDialect.FACEBOOK_SWIFT,
                "swift-state");
        registry.registerModel(
                "example.SharedState",
                SwiftModel.Kind.ENUM,
                ThriftAnnotationDialect.AIRLIFT_DRIFT,
                "drift-state");

        assertTrue(registry.isModelIdentity("swift-state"));
        assertTrue(registry.isModelIdentity("drift-state"));
        assertTrue(registry.historicalModels().containsKey(ModelRegistration.key(
                "example.SharedState", ThriftAnnotationDialect.FACEBOOK_SWIFT)));
        assertTrue(registry.historicalModels().containsKey(ModelRegistration.key(
                "example.SharedState", ThriftAnnotationDialect.AIRLIFT_DRIFT)));
    }
}
