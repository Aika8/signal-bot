package io.github.signalbot.application.trigger;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Issue #28: arming and pricing must never interleave. Every public method of {@link ExitGuard} that changes the
 * armed levels holds the same monitor as {@code onPrice}. Checked by reflection rather than by a timing-based
 * stress test, which could not fail reliably before the fix.
 */
class ExitGuardConcurrencyTest {

    private static final Set<String> MUTATORS = Set.of("arm", "armTrailing", "restore", "disarm", "onPrice");

    @Test
    void everyMutatingPublicMethodIsSynchronized() {
        List<Method> mutators = Arrays.stream(ExitGuard.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> MUTATORS.contains(m.getName()))
                .toList();

        Set<String> found = mutators.stream().map(Method::getName).collect(java.util.stream.Collectors.toSet());
        assertTrue(found.containsAll(MUTATORS), "expected public methods " + MUTATORS + ", found " + found);

        for (Method m : mutators) {
            assertTrue(Modifier.isSynchronized(m.getModifiers()),
                    () -> m.getName() + " changes the armed levels but is not synchronized");
        }
    }

    @Test
    void mutatorsAreInstanceMethodsSoTheyShareTheGuardMonitor() {
        Arrays.stream(ExitGuard.class.getDeclaredMethods())
                .filter(m -> MUTATORS.contains(m.getName()) && Modifier.isPublic(m.getModifiers()))
                .forEach(m -> assertTrue(!Modifier.isStatic(m.getModifiers()),
                        () -> m.getName() + " is static and would lock the class, not the guard"));
    }
}
