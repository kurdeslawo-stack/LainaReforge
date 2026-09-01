package pl.laina.reforge.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemCatalogGeneratorTest {
    @TempDir
    Path tempDir;

    @Test
    void mapsMaterialCmdAndModelWithSafeDefaults() throws IOException {
        Path zip = writeZip("mapping.zip", orderedEntries(
                "items/diamond_sword.json", modelJson(2350777, "swords/epic_slime_sword")));

        ItemCatalogGenerator.GenerationResult result = ItemCatalogGenerator.generate(zip);

        assertFalse(result.hasBlockingErrors());
        assertEquals(1, result.items().size());
        ItemCatalogGenerator.CatalogItem item = result.items().getFirst();
        assertEquals("diamond_sword", item.material());
        assertEquals(2350777, item.cmd());
        assertEquals("epic_slime_sword", item.model());
        assertEquals("swords/epic_slime_sword", item.modelPath());
        assertEquals("weapon", item.type());
        assertEquals("", item.wiki());
        assertEquals("", item.name());
        assertEquals(0, item.shards());
    }

    @Test
    void outputOrderDoesNotDependOnZipEntryOrder() throws IOException {
        LinkedHashMap<String, String> forward = orderedEntries(
                "items/netherite_sword.json", modelJson(20, "swords/twenty"),
                "items/bow.json", modelJson(30, "bows/thirty"),
                "items/diamond_sword.json", modelJson(10, "swords/ten"));
        LinkedHashMap<String, String> reverse = orderedEntries(
                "items/diamond_sword.json", modelJson(10, "swords/ten"),
                "items/bow.json", modelJson(30, "bows/thirty"),
                "items/netherite_sword.json", modelJson(20, "swords/twenty"));

        String first = ItemCatalogGenerator.renderYaml(
                ItemCatalogGenerator.generate(writeZip("forward.zip", forward)));
        String second = ItemCatalogGenerator.renderYaml(
                ItemCatalogGenerator.generate(writeZip("reverse.zip", reverse)));

        assertEquals(first, second);
        assertTrue(first.indexOf("\"bow:30\"") < first.indexOf("\"diamond_sword:10\""));
        assertTrue(first.indexOf("\"diamond_sword:10\"") < first.indexOf("\"netherite_sword:20\""));
    }

    @Test
    void collapsesExactDuplicateDefinitionsAndReportsThem() throws IOException {
        Path zip = writeZip("duplicate.zip", orderedEntries(
                "first/diamond_sword.json", modelJson(42, "swords/repeated"),
                "second/diamond_sword.json", modelJson(42, "swords/repeated")));

        ItemCatalogGenerator.GenerationResult result = ItemCatalogGenerator.generate(zip);

        assertFalse(result.hasBlockingErrors());
        assertEquals(1, result.items().size());
        assertEquals(1, result.duplicates().size());
        assertTrue(result.conflicts().isEmpty());
    }

    @Test
    void conflictReturnsErrorAndDoesNotWriteCatalog() throws IOException {
        Path zip = writeZip("conflict.zip", orderedEntries(
                "first/diamond_sword.json", modelJson(42, "swords/first"),
                "second/diamond_sword.json", modelJson(42, "swords/second")));
        Path catalog = tempDir.resolve("items.yml");
        Path report = tempDir.resolve("report.txt");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exitCode = ItemCatalogGenerator.execute(
                zip,
                catalog,
                report,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));

        assertEquals(2, exitCode);
        assertFalse(Files.exists(catalog));
        assertTrue(Files.readString(report).contains("Conflicts: 1"));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("Catalog not written"));
    }

    @Test
    void sameCmdOnDifferentMaterialsIsAllowed() throws IOException {
        Path zip = writeZip("shared-cmd.zip", orderedEntries(
                "items/diamond_sword.json", modelJson(77, "swords/shared"),
                "items/netherite_sword.json", modelJson(77, "swords/shared")));

        ItemCatalogGenerator.GenerationResult result = ItemCatalogGenerator.generate(zip);

        assertFalse(result.hasBlockingErrors());
        assertEquals(2, result.items().size());
        assertEquals(1, result.uniqueCmdCount());
        assertEquals(2, result.uniqueKeyCount());
        assertEquals(1, result.modelsOnMultipleMaterials().size());
    }

    @Test
    void selectsBaseModelFromMultiStateDefinition() throws IOException {
        String json = """
                {
                  "model": {
                    "type": "range_dispatch",
                    "property": "custom_model_data",
                    "entries": [{
                      "threshold": 99,
                      "model": {
                        "type": "minecraft:condition",
                        "property": "minecraft:using_item",
                        "on_false": {"type": "minecraft:model", "model": "bows/base_bow"},
                        "on_true": {
                          "type": "minecraft:range_dispatch",
                          "property": "minecraft:use_duration",
                          "fallback": {"type": "minecraft:model", "model": "bows/base_bow_pulling_0"},
                          "entries": [{
                            "threshold": 0.9,
                            "model": {"type": "minecraft:model", "model": "bows/base_bow_pulling_2"}
                          }]
                        }
                      }
                    }]
                  }
                }
                """;
        Path zip = writeZip("states.zip", orderedEntries("items/bow.json", json));

        ItemCatalogGenerator.CatalogItem item = ItemCatalogGenerator.generate(zip).items().getFirst();

        assertEquals("base_bow", item.model());
        assertEquals("bows/base_bow", item.modelPath());
        assertEquals("bow", item.type());
    }

    @Test
    void reportsInvalidCmdAndMalformedJson() throws IOException {
        String invalidCmd = """
                {"model":{"type":"range_dispatch","property":"custom_model_data","entries":[
                  {"threshold":1.5,"model":{"type":"model","model":"misc/bad"}}
                ]}}
                """;
        Path zip = writeZip("invalid.zip", orderedEntries(
                "items/echo_shard.json", invalidCmd,
                "items/book.json", "{not-json"));

        ItemCatalogGenerator.GenerationResult result = ItemCatalogGenerator.generate(zip);

        assertTrue(result.hasBlockingErrors());
        assertEquals(1, result.invalidCmd().size());
        assertEquals(1, result.parseErrors().size());
    }

    private Path writeZip(String name, LinkedHashMap<String, String> entries) throws IOException {
        Path path = tempDir.resolve(name);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return path;
    }

    private static String modelJson(int cmd, String modelPath) {
        return """
                {
                  "model": {
                    "type": "range_dispatch",
                    "property": "custom_model_data",
                    "entries": [{
                      "threshold": %d,
                      "model": {"type": "model", "model": "%s"}
                    }]
                  }
                }
                """.formatted(cmd, modelPath);
    }

    private static LinkedHashMap<String, String> orderedEntries(String... values) {
        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            entries.put(values[index], values[index + 1]);
        }
        return entries;
    }
}
