package io.github.thriftannotationlint.internal.bytecode;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class MethodParameterMetadataTest {
    @Test
    void retainsDistinctParanamerAndDriftLocalVariableViews() throws Exception {
        MethodDescriptorParser.Layout layout =
                new MethodDescriptorParser().layout("(II)V", true);
        MethodParameterMetadata metadata = new MethodParameterMetadata();
        metadata.addLocalVariable("left", 0);
        metadata.addLocalVariable("wovenAlias", 0);
        metadata.addLocalVariable("right", 1);

        MethodParameterMetadata.Lookups lookups = metadata.resolve(layout);

        assertEquals(Arrays.asList("left", "wovenAlias"), lookups.swift().names());
        assertEquals(Arrays.asList("wovenAlias", "right"), lookups.drift().names());
    }

    @Test
    void completeMethodParametersOverrideOnlyTheDriftView() throws Exception {
        MethodDescriptorParser.Layout layout =
                new MethodDescriptorParser().layout("(II)V", true);
        MethodParameterMetadata metadata = new MethodParameterMetadata();
        metadata.addLocalVariable("lvtLeft", 0);
        metadata.addLocalVariable("lvtRight", 1);
        metadata.setMethodParameterNames(Arrays.asList("declaredLeft", "declaredRight"));

        MethodParameterMetadata.Lookups lookups = metadata.resolve(layout);

        assertEquals(Arrays.asList("lvtLeft", "lvtRight"), lookups.swift().names());
        assertEquals(Arrays.asList("declaredLeft", "declaredRight"), lookups.drift().names());
    }

    @Test
    void partialMethodParametersRemainAReflectionFallbackWithoutLvt() throws Exception {
        MethodDescriptorParser.Layout layout =
                new MethodDescriptorParser().layout("(II)V", true);
        MethodParameterMetadata metadata = new MethodParameterMetadata();
        metadata.setMethodParameterNames(Arrays.asList("declaredLeft", null));

        ParameterNameLookup drift = metadata.resolve(layout).drift();

        assertFalse(drift.isFound());
        assertEquals(Arrays.asList("declaredLeft", "arg1"), drift.fallbackNames());
    }
}
