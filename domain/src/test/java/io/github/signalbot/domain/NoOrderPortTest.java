package io.github.signalbot.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * README, first rule: no port can place an order. Every interface in {@code io.github.signalbot.domain.port}
 * is loaded by name and its declared methods are checked against a list of forbidden words. The paper wallet's
 * methods are the only exception, by exact name.
 */
class NoOrderPortTest {

    private static final String PORT_PACKAGE = "io.github.signalbot.domain.port";
    private static final Path PORT_SOURCES = Path.of("src/main/java/io/github/signalbot/domain/port");
    private static final List<String> FORBIDDEN = List.of("order", "place", "submit", "trade");

    /** Interface simple name -> method names allowed despite the rule. Only the paper wallet, by exact name. */
    private static final Map<String, Set<String>> EXCEPTIONS = Map.of(
            "PaperWallet", Set.of("apply", "applyAt"));

    @Test
    void noPortDeclaresAnOrderMethod() throws Exception {
        List<Class<?>> ports = portInterfaces();
        assertFalse(ports.isEmpty(), "no port interfaces found under " + PORT_SOURCES.toAbsolutePath());

        List<String> found = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        for (Class<?> port : ports) {
            for (Method method : port.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                String entry = port.getSimpleName() + "." + method.getName();
                found.add(entry);
                if (violates(port.getSimpleName(), method.getName())) {
                    violations.add(entry);
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "port methods that could place an order: " + violations + "; all port methods found: " + found);
    }

    @Test
    void paperWalletExceptionsAreRealMethods() throws Exception {
        for (Map.Entry<String, Set<String>> exception : EXCEPTIONS.entrySet()) {
            Class<?> port = Class.forName(PORT_PACKAGE + "." + exception.getKey());
            Set<String> declared = new java.util.TreeSet<>();
            Arrays.stream(port.getDeclaredMethods()).map(Method::getName).forEach(declared::add);
            assertTrue(declared.containsAll(exception.getValue()),
                    "stale exception for " + exception.getKey() + ": " + exception.getValue()
                            + " not all in " + declared);
        }
    }

    @Test
    void ruleCatchesForbiddenNamesCaseInsensitively() {
        assertTrue(violates("Broker", "placeOrder"));
        assertTrue(violates("Broker", "SUBMIT"));
        assertTrue(violates("Broker", "tradeNow"));
        assertTrue(violates("Broker", "reorder"));
        assertTrue(violates("PaperWallet", "placeOrder"));
        assertFalse(violates("PaperWallet", "applyAt"));
        assertFalse(violates("Notifier", "send"));
    }

    @Test
    void exceptionHoldsOnlyForPaperWallet() {
        assertEquals(Set.of("PaperWallet"), EXCEPTIONS.keySet());
        assertFalse(violates("Broker", "apply"));
    }

    private static boolean violates(String portName, String methodName) {
        if (EXCEPTIONS.getOrDefault(portName, Set.of()).contains(methodName)) {
            return false;
        }
        String lower = methodName.toLowerCase(Locale.ROOT);
        return FORBIDDEN.stream().anyMatch(lower::contains);
    }

    private static List<Class<?>> portInterfaces() throws IOException, ClassNotFoundException {
        List<String> classNames;
        try (Stream<Path> files = Files.walk(PORT_SOURCES)) {
            classNames = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(p -> PORT_SOURCES.relativize(p).toString())
                    .map(rel -> rel.substring(0, rel.length() - ".java".length()).replace('/', '.').replace('\\', '.'))
                    .filter(rel -> !rel.endsWith("package-info"))
                    .sorted()
                    .toList();
        }
        List<Class<?>> interfaces = new ArrayList<>();
        for (String name : classNames) {
            Class<?> type = Class.forName(PORT_PACKAGE + "." + name);
            if (type.isInterface()) {
                interfaces.add(type);
            }
        }
        return interfaces;
    }
}
