package com.peoplesserver.supportermod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards {@code manifest.json} against the two ways it has actually broken.
 *
 * <p><b>This exists because a malformed manifest does not degrade — it stops the server.</b>
 * {@code PluginManager.loadPendingJavaPlugin} decodes every manifest during boot, and a failure
 * there throws out of {@code HytaleServer.boot}, so one bad plugin manifest takes down the whole
 * server with "Failed to boot HytaleServer!" and a codec stack trace. Every other plugin on the
 * box goes down with it. That happened on 2026-08-17 with a 0.7.2 build.
 *
 * <p>The trap is that the usual sanity checks pass. Gson and PowerShell's {@code ConvertFrom-Json}
 * are both lenient about raw control characters inside strings, so a manifest containing a literal
 * newline parses fine on the workstation and is rejected by Hytale's stricter
 * {@code RawJsonReader} on the server. Validating with a lenient parser proves nothing, so this
 * test scans the bytes instead.
 */
class ManifestTest {

    private static final Path MANIFEST = Path.of("src", "main", "resources", "manifest.json");
    private static final Path BUILD_FILE = Path.of("build.gradle.kts");

    @Test
    @DisplayName("no raw control character appears inside a JSON string")
    void noRawControlCharactersInStrings() throws IOException {
        String json = Files.readString(MANIFEST, StandardCharsets.UTF_8);

        boolean inString = false;
        boolean escaped = false;
        int line = 1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\n') {
                line++;
            }
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString && c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString && c < 0x20) {
                // A changelog entry pasted across two lines is the way this happens: the value
                // reads perfectly in an editor and is invalid JSON. Escape it as \n, or keep the
                // value on one line.
                fail(String.format(
                        "manifest.json line %d contains a raw control character (0x%02X) inside a "
                                + "string. Hytale's RawJsonReader rejects this and the server will "
                                + "not boot.",
                        line, (int) c));
            }
        }
        assertFalse(inString, "unterminated string in manifest.json");
    }

    @Test
    @DisplayName("no byte-order mark")
    void noByteOrderMark() throws IOException {
        byte[] bytes = Files.readAllBytes(MANIFEST);
        boolean bom = bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF;
        assertFalse(bom, "manifest.json starts with a UTF-8 BOM, which the decoder reads as part "
                + "of the first key. PowerShell's Set-Content and Out-File add one by default.");
    }

    @Test
    @DisplayName("the manifest version matches the Gradle version")
    void versionsAgree() throws IOException {
        // Shipped a jar whose filename disagreed with the version the server reports twice
        // already, both times by bumping one and forgetting the other.
        String manifestVersion = firstGroup(
                Pattern.compile("\"Version\"\\s*:\\s*\"([^\"]+)\""),
                Files.readString(MANIFEST, StandardCharsets.UTF_8),
                "Version in manifest.json");
        String gradleVersion = firstGroup(
                Pattern.compile("(?m)^version\\s*=\\s*\"([^\"]+)\""),
                Files.readString(BUILD_FILE, StandardCharsets.UTF_8),
                "version in build.gradle.kts");

        assertEquals(gradleVersion, manifestVersion,
                "the jar filename and the version the server reports must agree");
    }

    @Test
    @DisplayName("the changelog leads with the version being built")
    void changelogLeadsWithCurrentVersion() throws IOException {
        String json = Files.readString(MANIFEST, StandardCharsets.UTF_8);
        String version = firstGroup(
                Pattern.compile("\"Version\"\\s*:\\s*\"([^\"]+)\""), json, "Version");
        String description = firstGroup(
                Pattern.compile("\"Description\"\\s*:\\s*\"(.*?)(?<!\\\\)\"", Pattern.DOTALL),
                json, "Description");

        assertTrue(description.startsWith("v" + version),
                "the Description is the changelog and must open with v" + version
                        + ", so the release notes and the version cannot drift apart. It opens: "
                        + description.substring(0, Math.min(40, description.length())));
    }

    private static String firstGroup(Pattern pattern, String text, String what) {
        Matcher m = pattern.matcher(text);
        assertTrue(m.find(), "could not find " + what);
        return m.group(1);
    }
}
