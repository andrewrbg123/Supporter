package com.peoplesserver.supportermod;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the shipped asset pack.
 *
 * <p><b>A bad asset does not degrade — it stops the server.</b> On 2026-08-18 a 0.9.0 build
 * declared an item whose model pointed at {@code Cosmetics/Capes/Cape_Basic.blockymodel}, and the
 * asset validator refused it:
 *
 * <pre>
 * FAIL: Common Asset '...' must be within the root:
 *       [Blocks/, Items/, Resources/, NPC/, VFX/, Consumable/]
 * </pre>
 *
 * <p>That single rejection produced "Asset validation FAILED with 1 reason(s)" and shut the whole
 * server down at boot. The rule is not written anywhere we control, so it gets encoded here where
 * the build can enforce it.
 */
class AssetPackTest {

    private static final Path ITEMS = Path.of("src", "main", "resources", "Server", "Item");
    private static final Path COMMON = Path.of("src", "main", "resources", "Common");

    /** Exactly the roots the server named when it rejected the cape. */
    private static final List<String> ALLOWED_ROOTS =
            List.of("Blocks/", "Items/", "Resources/", "NPC/", "VFX/", "Consumable/");

    private static final Pattern MODEL = Pattern.compile("\"Model\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TEXTURE = Pattern.compile("\"Texture\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ICON = Pattern.compile("\"Icon\"\\s*:\\s*\"([^\"]+)\"");

    @Test
    @DisplayName("every item model and texture sits under an allowed asset root")
    void assetRootsAreAllowed() throws IOException {
        for (Path item : itemFiles()) {
            String json = Files.readString(item, StandardCharsets.UTF_8);
            for (Pattern pattern : List.of(MODEL, TEXTURE)) {
                Matcher m = pattern.matcher(json);
                while (m.find()) {
                    String ref = m.group(1);
                    boolean ok = ALLOWED_ROOTS.stream().anyMatch(ref::startsWith);
                    if (!ok) {
                        fail(item.getFileName() + " references '" + ref + "', which is outside "
                                + ALLOWED_ROOTS + ". The server will refuse the asset and abort "
                                + "boot for every plugin, not just this one.");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("every texture and icon we reference is actually shipped")
    void referencedFilesExist() throws IOException {
        for (Path item : itemFiles()) {
            String json = Files.readString(item, StandardCharsets.UTF_8);
            // Only our own files are checked. A Model may legitimately point at vanilla geometry
            // that this jar does not carry — which is the whole reason the cape needs no
            // modelling — so it is excluded rather than asserted into existence.
            for (Pattern pattern : List.of(TEXTURE, ICON)) {
                Matcher m = pattern.matcher(json);
                while (m.find()) {
                    Path shipped = COMMON.resolve(m.group(1));
                    assertTrue(Files.isRegularFile(shipped),
                            item.getFileName() + " references " + m.group(1)
                                    + " but " + shipped + " is not in the jar. A missing texture "
                                    + "renders as nothing, silently.");
                }
            }
        }
    }

    @Test
    @DisplayName("the pack declares itself in the manifest")
    void manifestDeclaresAssetPack() throws IOException {
        String manifest = Files.readString(
                Path.of("src", "main", "resources", "manifest.json"), StandardCharsets.UTF_8);
        boolean shipsAssets = Files.isDirectory(COMMON) || Files.isDirectory(ITEMS);
        if (shipsAssets) {
            assertTrue(manifest.contains("\"IncludesAssetPack\": true"),
                    "assets are shipped but IncludesAssetPack is not true, so the server will "
                            + "never load them");
        }
    }

    private static List<Path> itemFiles() throws IOException {
        if (!Files.isDirectory(ITEMS)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(ITEMS)) {
            List<Path> found = new ArrayList<>();
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(found::add);
            return found;
        }
    }
}
