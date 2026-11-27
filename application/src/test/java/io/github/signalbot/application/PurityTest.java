package io.github.signalbot.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * README: "The domain knows nothing about Spring, HTTP, or SQL", and dependencies point inward. Every Java source
 * of {@code domain} and {@code application} is read line by line; an import (plain or static) of a framework or
 * I/O package fails the test, and the message lists every offending file:line.
 */
class PurityTest {

    /** Paths relative to the {@code application} module, Gradle's working directory for its tests. */
    private static final List<Path> SOURCE_ROOTS = List.of(
            Path.of("../domain/src/main/java"),
            Path.of("src/main/java"));

    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "org.springframework", "java.sql", "java.net", "jakarta.", "com.anthropic", "org.telegram");

    private static final Pattern FORBIDDEN_IMPORT = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?(?:"
                    + String.join("|", FORBIDDEN_PREFIXES.stream().map(Pattern::quote).toList())
                    + ")\\b");

    @Test
    void domainAndApplicationImportNoFramework() throws IOException {
        List<Path> scanned = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        for (Path root : SOURCE_ROOTS) {
            assertTrue(Files.isDirectory(root), "source root missing: " + root.toAbsolutePath().normalize());
            scanned.addAll(javaFiles(root));
            violations(root).forEach(v -> violations.add(root + "/" + v));
        }

        assertFalse(scanned.isEmpty(), "no Java sources found under " + SOURCE_ROOTS);
        assertTrue(violations.isEmpty(),
                "framework imports in domain/application (" + violations.size() + "):\n"
                        + String.join("\n", violations));
    }

    @Test
    void ruleCatchesEveryForbiddenPrefix() {
        assertTrue(forbidden("import org.springframework.stereotype.Component;"));
        assertTrue(forbidden("import java.sql.Connection;"));
        assertTrue(forbidden("import java.net.URI;"));
        assertTrue(forbidden("import jakarta.annotation.Nonnull;"));
        assertTrue(forbidden("import com.anthropic.client.AnthropicClient;"));
        assertTrue(forbidden("import org.telegram.telegrambots.meta.TelegramBotsApi;"));
        assertTrue(forbidden("  import static org.springframework.util.Assert.notNull;"));
        assertTrue(forbidden("import java.sql.*;"));
    }

    @Test
    void ruleLeavesPlainJavaAlone() {
        assertFalse(forbidden("import java.math.BigDecimal;"));
        assertFalse(forbidden("import java.time.Instant;"));
        assertFalse(forbidden("import java.util.List;"));
        assertFalse(forbidden("import io.github.signalbot.domain.port.PriceHistory;"));
        assertFalse(forbidden("import java.sqlx.Thing;"));
        assertFalse(forbidden("import org.springframeworkish.Thing;"));
        assertFalse(forbidden("// see java.net.URI for the format"));
        assertFalse(forbidden("String s = \"import java.sql.Connection;\";"));
    }

    @Test
    void reportsEveryOffendingFileAndLine(@TempDir Path root) throws IOException {
        Path pkg = Files.createDirectories(root.resolve("x/y"));
        Files.writeString(pkg.resolve("Bad.java"), String.join("\n",
                "package x.y;",
                "",
                "import java.math.BigDecimal;",
                "import java.sql.Connection;",
                "import static org.springframework.util.Assert.notNull;",
                "class Bad {}"));
        Files.writeString(pkg.resolve("Good.java"), "package x.y;\n\nimport java.util.List;\nclass Good {}\n");
        Files.writeString(root.resolve("notes.txt"), "import java.net.URI;\n");

        List<String> found = violations(root);

        assertEquals(List.of(
                "x/y/Bad.java:4: import java.sql.Connection;",
                "x/y/Bad.java:5: import static org.springframework.util.Assert.notNull;"), found);
    }

    private static boolean forbidden(String line) {
        return FORBIDDEN_IMPORT.matcher(line).find();
    }

    private static List<String> violations(Path root) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(root)) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (forbidden(lines.get(i))) {
                    String name = root.relativize(file).toString().replace('\\', '/');
                    violations.add(name + ":" + (i + 1) + ": " + lines.get(i).strip());
                }
            }
        }
        return violations;
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }
}
