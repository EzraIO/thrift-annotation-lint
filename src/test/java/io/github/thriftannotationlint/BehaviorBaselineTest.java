package io.github.thriftannotationlint;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Prevents architecture tests from silently replacing the original behavior suite. */
class BehaviorBaselineTest {
    private static final int ORIGINAL_BEHAVIOR_TESTS = 149;
    private static final String ORIGINAL_BEHAVIOR_IDENTITIES_SHA_256 =
            "88f7d989e3337e24b3a7315dbd64152e34927d0a4ecb024cbd8d19f2813f6a36";

    @Test
    void preservesTheOriginalBehaviorTestBaseline() {
        Class<?>[] baselineSuites = {
                ThriftAnnotationLintProcessorTest.class,
                ClasspathValidationTest.class,
                OfficialSwiftCompatibilityTest.class,
                ProcessorRegistrationTest.class
        };
        List<String> testIdentities = new ArrayList<String>();
        for (Class<?> suite : baselineSuites) {
            assertFalse(
                    suite.getAnnotation(Disabled.class) != null,
                    suite.getName() + " must remain enabled");
            for (Method method : suite.getDeclaredMethods()) {
                if (method.getAnnotation(Test.class) != null) {
                    assertFalse(
                            method.getAnnotation(Disabled.class) != null,
                            suite.getName() + "#" + method.getName()
                                    + " must remain enabled");
                    testIdentities.add(suite.getName() + "#" + method.getName());
                }
            }
        }

        Collections.sort(testIdentities);
        assertEquals(
                ORIGINAL_BEHAVIOR_TESTS,
                testIdentities.size(),
                "The original behavior suites must remain an exact 149-test baseline; add new "
                        + "architecture coverage in a separate suite.");
        assertEquals(
                ORIGINAL_BEHAVIOR_IDENTITIES_SHA_256,
                sha256(testIdentities),
                "An original behavior test was renamed or replaced. Preserve its identity and "
                        + "add new coverage separately.");
    }

    private String sha256(List<String> identities) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String identity : identities) {
                digest.update(identity.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            StringBuilder value = new StringBuilder();
            for (byte item : digest.digest()) {
                value.append(String.format("%02x", item & 0xff));
            }
            return value.toString();
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Every supported JDK must provide SHA-256", impossible);
        }
    }
}
