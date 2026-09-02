package pl.laina.reforge.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemEconomySerializationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void cacheRoundTripPreservesUtf8WikiText() throws Exception {
        Path cachePath = temporaryDirectory.resolve("cache.xml");
        ItemEconomyAnalyzer.EconomyCache cache = new ItemEconomyAnalyzer.EconomyCache();
        cache.put(new ItemEconomyAnalyzer.EconomyPage(
                "Żółty_Miecz", true, 42L, "2026-09-01T00:00:00Z", "Można go zdobyć z klucza."));

        cache.save(cachePath);
        ItemEconomyAnalyzer.EconomyCache loaded = ItemEconomyAnalyzer.EconomyCache.load(cachePath);

        assertEquals("Można go zdobyć z klucza.", loaded.page("Żółty Miecz").wikitext());
        assertTrue(Files.readString(cachePath, StandardCharsets.UTF_8).startsWith("<?xml version=\"1.0\""));
    }

    @Test
    void analysisUsesRealYamlQuotesInsteadOfEscapedDelimiters() {
        ItemEconomyAnalyzer.CatalogRecord record = new ItemEconomyAnalyzer.CatalogRecord(
                "diamond_sword:1", "diamond_sword", 1, "swords/test",
                "Test_Item", "Test Item", 0);
        ItemEconomyAnalyzer.EconomyCache cache = new ItemEconomyAnalyzer.EconomyCache();
        cache.put(new ItemEconomyAnalyzer.EconomyPage(
                "Test_Item", true, 1L, "", "== Jak zdobyć? ==\n* Można stworzyć w stole kowalskim."));
        ItemEconomyAnalyzer.AnalysisResult result = ItemEconomyAnalyzer.analyze(
                new ItemEconomyAnalyzer.Catalog(List.of(record)), cache, List.of());

        String yaml = ItemEconomyAnalyzer.renderAnalysis(result);

        assertTrue(yaml.contains("  \"Test_Item\":"));
        assertFalse(yaml.contains("\\\"Test_Item\\\""));
    }

    @Test
    void fishingAndFarmingAreExplicitFarmableSources() {
        ItemEconomyAnalyzer.CatalogRecord record = new ItemEconomyAnalyzer.CatalogRecord(
                "cod:1", "cod", 1, "fish/test", "Test_Item", "Test Item", 0);
        ItemEconomyAnalyzer.ItemAnalysis fishing = ItemEconomyAnalyzer.analyzePage(
                record.wiki(), record.name(), List.of(record), new ItemEconomyAnalyzer.EconomyPage(
                        "Test_Item", true, 1L, "", "== Jak zdobyć? ==\n* Można ją złowić Magiczną Wędką."));
        ItemEconomyAnalyzer.ItemAnalysis farming = ItemEconomyAnalyzer.analyzePage(
                record.wiki(), record.name(), List.of(record), new ItemEconomyAnalyzer.EconomyPage(
                        "Test_Item", true, 1L, "", "== Jak zdobyć? ==\n* Zbierając posadzone marchewki."));

        assertTrue(fishing.tags().contains(ItemEconomyAnalyzer.SupplyTag.INFINITE_OR_FARMABLE));
        assertTrue(farming.tags().contains(ItemEconomyAnalyzer.SupplyTag.INFINITE_OR_FARMABLE));
        assertEquals(ItemEconomyAnalyzer.FactState.TRUE, fishing.renewable());
        assertEquals(ItemEconomyAnalyzer.FactState.TRUE, farming.renewable());
    }
}
