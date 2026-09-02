package pl.laina.reforge.catalog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemEconomyAnalyzerTest {
    @Test
    void recognizesUnambiguousCraftAcquisition() {
        ItemEconomyAnalyzer.ItemAnalysis item = analyze("""
                == Jak zdobyć? ==
                * Można stworzyć w stole kowalskim z netherytowego miecza.
                [[Kategoria:Przedmioty]][[Kategoria:Craftowalne]]
                """);

        assertEquals(ItemEconomyAnalyzer.FactState.TRUE, item.craftable());
        assertTrue(item.tags().contains(ItemEconomyAnalyzer.SupplyTag.CRAFT));
        assertEquals(ItemEconomyAnalyzer.ProposalValue.UNKNOWN, item.proposal().value());
        assertTrue(item.reviewReasons().isEmpty());
    }

    @Test
    void preservesSeveralSourcesAndQueuesMixedImpact() {
        ItemEconomyAnalyzer.ItemAnalysis item = analyze("""
                == Jak zdobyć? ==
                * Nagroda z Klucza Letniego podczas eventu.
                * Można kupić w sklepie eventowym.
                [[Kategoria:Przedmioty]][[Kategoria:Eventowe]]
                """);

        assertEquals(ItemEconomyAnalyzer.FactState.TRUE, item.fromKey());
        assertEquals(ItemEconomyAnalyzer.FactState.TRUE, item.fromEvent());
        assertEquals(ItemEconomyAnalyzer.FactState.TRUE, item.fromShop());
        assertTrue(item.reviewReasons().contains("MIXED_SUPPLY_IMPACT"));
        assertEquals(ItemEconomyAnalyzer.ProposalValue.UNKNOWN, item.proposal().value());
    }

    @Test
    void unknownSourceIsNotGuessed() {
        ItemEconomyAnalyzer.ItemAnalysis item = analyze("""
                == Jak zdobyć? ==
                * Można go zdobyć w tajemniczy sposób.
                [[Kategoria:Przedmioty]]
                """);

        assertTrue(item.tags().contains(ItemEconomyAnalyzer.SupplyTag.UNKNOWN));
        assertEquals(ItemEconomyAnalyzer.FactState.UNKNOWN, item.fromDrop());
        assertTrue(item.reviewReasons().contains("UNCLEAR_SOURCE"));
    }

    @Test
    void recognizesEventFromWikiCategoryWithoutTreatingItAsDecision() {
        ItemEconomyAnalyzer.ItemAnalysis item = analyze("""
                == Jak zdobyć? ==
                * Przedmiot dostępny tylko podczas eventu Wakacyjna Wyspa.
                [[Kategoria:Przedmioty]][[Kategoria:Eventowe]]
                """);

        assertEquals(ItemEconomyAnalyzer.FactState.TRUE, item.fromEvent());
        assertTrue(item.tags().contains(ItemEconomyAnalyzer.SupplyTag.EVENT));
        assertEquals(ItemEconomyAnalyzer.ProposalValue.UNKNOWN, item.proposal().value());
    }

    @Test
    void recognizesKeyReward() {
        ItemEconomyAnalyzer.ItemAnalysis item = analyze("""
                == Jak zdobyć? ==
                * Można wylosować z Klucza Premium.
                [[Kategoria:Przedmioty]]
                """);

        assertEquals(ItemEconomyAnalyzer.FactState.TRUE, item.fromKey());
        assertTrue(item.tags().contains(ItemEconomyAnalyzer.SupplyTag.KEY_REWARD));
        assertEquals(ItemEconomyAnalyzer.ProposalValue.UNKNOWN, item.proposal().value());
    }

    @Test
    void recognizesRepeatableFarmableDrop() {
        ItemEconomyAnalyzer.ItemAnalysis item = analyze("""
                == Jak zdobyć? ==
                * Wypada po pokonaniu bossa, którego można pokonać wielokrotnie.
                [[Kategoria:Przedmioty]]
                """);

        assertEquals(ItemEconomyAnalyzer.FactState.TRUE, item.fromDrop());
        assertEquals(ItemEconomyAnalyzer.FactState.TRUE, item.repeatable());
        assertEquals(ItemEconomyAnalyzer.FactState.TRUE, item.renewable());
        assertTrue(item.tags().contains(ItemEconomyAnalyzer.SupplyTag.INFINITE_OR_FARMABLE));
    }

    @Test
    void dailyRewardsSystemDoesNotMakeSpecificRewardRepeatable() {
        ItemEconomyAnalyzer.ItemAnalysis item = analyze("""
                == Jak zdobyć? ==
                * Jest nagrodą za 23 dzień codziennego wpisywania komendy /rewards.
                [[Kategoria:Przedmioty]]
                """);

        assertEquals(ItemEconomyAnalyzer.FactState.UNKNOWN, item.repeatable());
        assertEquals(ItemEconomyAnalyzer.FactState.UNKNOWN, item.renewable());
        assertFalse(item.tags().contains(ItemEconomyAnalyzer.SupplyTag.REPEATABLE));
        assertFalse(item.tags().contains(ItemEconomyAnalyzer.SupplyTag.INFINITE_OR_FARMABLE));
        assertEquals(ItemEconomyAnalyzer.ProposalValue.UNKNOWN, item.proposal().value());
    }

    @Test
    void dailyAloneIsNotEvidenceOfRepeatableSupply() {
        ItemEconomyAnalyzer.ItemAnalysis item = analyze("""
                == Jak zdobyć? ==
                * Daily reward for logging into the server.
                [[Kategoria:Przedmioty]]
                """);

        assertEquals(ItemEconomyAnalyzer.FactState.UNKNOWN, item.repeatable());
        assertFalse(item.tags().contains(ItemEconomyAnalyzer.SupplyTag.REPEATABLE));
    }

    @Test
    void recognizesExplicitlyRepeatableAcquisitionOfSameItem() {
        ItemEconomyAnalyzer.ItemAnalysis item = analyze("""
                == Jak zdobyć? ==
                * Ten przedmiot można zdobyć wielokrotnie po pokonaniu bossa.
                [[Kategoria:Przedmioty]]
                """);

        assertEquals(ItemEconomyAnalyzer.FactState.TRUE, item.repeatable());
        assertEquals(ItemEconomyAnalyzer.FactState.TRUE, item.renewable());
        assertTrue(item.tags().contains(ItemEconomyAnalyzer.SupplyTag.INFINITE_OR_FARMABLE));
    }

    @Test
    void recognizesSafeDropLanguageVariants() {
        ItemEconomyAnalyzer.ItemAnalysis mayDrop = analyze("""
                == Jak zdobyć? ==
                * Może wypaść z Płomyka (2% szans).
                """);
        ItemEconomyAnalyzer.ItemAnalysis dropsFrom = analyze("""
                == Jak zdobyć? ==
                * Wypada z Płomyka.
                """);

        assertEquals(ItemEconomyAnalyzer.FactState.TRUE, mayDrop.fromDrop());
        assertEquals(ItemEconomyAnalyzer.FactState.TRUE, dropsFrom.fromDrop());
        assertEquals(ItemEconomyAnalyzer.ProposalValue.UNKNOWN, mayDrop.proposal().value());
    }

    @Test
    void recognizesSafeFishingLanguageVariants() {
        ItemEconomyAnalyzer.ItemAnalysis angling = analyze("""
                == Jak zdobyć? ==
                * Można zdobyć łowiąc na /warp Ryby.
                """);
        ItemEconomyAnalyzer.ItemAnalysis fromFishing = analyze("""
                == Jak zdobyć? ==
                * Przedmiot jest dostępny z łowienia.
                """);

        assertEquals(ItemEconomyAnalyzer.FactState.TRUE, angling.repeatable());
        assertEquals(ItemEconomyAnalyzer.FactState.TRUE, fromFishing.repeatable());
        assertTrue(angling.tags().contains(ItemEconomyAnalyzer.SupplyTag.INFINITE_OR_FARMABLE));
    }

    @Test
    void recognizesExplicitLeafHarvestAsFarmingSource() {
        ItemEconomyAnalyzer.ItemAnalysis item = analyze("""
                == Jak zdobyć? ==
                * Zbierając jabłka z liści przy pomocy Ametystowej Kosy.
                """);

        assertEquals(ItemEconomyAnalyzer.FactState.TRUE, item.repeatable());
        assertEquals(ItemEconomyAnalyzer.FactState.TRUE, item.renewable());
        assertTrue(item.tags().contains(ItemEconomyAnalyzer.SupplyTag.INFINITE_OR_FARMABLE));
    }

    @Test
    void recognizesExplicitCurrencyExchangeAsShopWithoutAssumingRepeatability() {
        ItemEconomyAnalyzer.ItemAnalysis item = analyze("""
                == Jak zdobyć? ==
                * Dostępne za 1 Morskiego Coina w Morskiej Osadzie.
                """);

        assertEquals(ItemEconomyAnalyzer.FactState.TRUE, item.fromShop());
        assertEquals(ItemEconomyAnalyzer.FactState.UNKNOWN, item.repeatable());
        assertEquals(ItemEconomyAnalyzer.ProposalValue.UNKNOWN, item.proposal().value());
    }

    @Test
    void missingAcquisitionDataGoesToManualReview() {
        ItemEconomyAnalyzer.ItemAnalysis item = analyze("""
                Specjalny miecz o unikalnym wyglądzie.
                == Opis ==
                Zadaje dodatkowe obrażenia.
                [[Kategoria:Przedmioty]]
                """);

        assertTrue(item.evidence().isEmpty());
        assertTrue(item.reviewReasons().contains("INSUFFICIENT_DATA"));
        assertEquals(ItemEconomyAnalyzer.ProposalValue.UNKNOWN, item.proposal().value());
    }

    @Test
    void analysisDoesNotChangeCatalogShards() {
        ItemEconomyAnalyzer.Catalog catalog = ItemEconomyAnalyzer.Catalog.parse(catalogYaml(7));
        ItemEconomyAnalyzer.EconomyCache cache = new ItemEconomyAnalyzer.EconomyCache();
        cache.put(page("== Jak zdobyć? ==\n* Można stworzyć w stole kowalskim."));

        ItemEconomyAnalyzer.analyze(catalog, cache, List.of());

        assertEquals(7, catalog.records().getFirst().shards());
    }

    @Test
    void analyzerIsNotRegisteredInPluginRuntime() throws IOException {
        String pluginYaml = Files.readString(Path.of("src/main/resources/plugin.yml"));

        assertFalse(pluginYaml.contains("ItemEconomyAnalyzer"));
        assertFalse(pluginYaml.contains("item-economy"));
    }

    private static ItemEconomyAnalyzer.ItemAnalysis analyze(String wikitext) {
        ItemEconomyAnalyzer.CatalogRecord record = record(0);
        return ItemEconomyAnalyzer.analyzePage(
                record.wiki(), record.name(), List.of(record), page(wikitext));
    }

    private static ItemEconomyAnalyzer.EconomyPage page(String wikitext) {
        return new ItemEconomyAnalyzer.EconomyPage("Test_Item", true, 1L, "2026-01-01T00:00:00Z", wikitext);
    }

    private static ItemEconomyAnalyzer.CatalogRecord record(int shards) {
        return new ItemEconomyAnalyzer.CatalogRecord(
                "diamond_sword:1", "diamond_sword", 1, "swords/test", "Test_Item", "Test Item", shards);
    }

    private static String catalogYaml(int shards) {
        return """
                items:
                  "diamond_sword:1":
                    material: "diamond_sword"
                    cmd: 1
                    model: "test"
                    model_path: "swords/test"
                    type: "weapon"
                    wiki: "Test_Item"
                    name: "Test Item"
                    shards: %d
                """.formatted(shards);
    }
}
