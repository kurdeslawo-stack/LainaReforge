package pl.laina.reforge.catalog;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiCatalogMapperTest {
    @Test
    void mapsExactImageToSingleItemPage() {
        WikiCatalogMapper.CatalogDocument catalog = catalog(record("diamond_sword", 1, "epic_slime_sword", 0));
        WikiCatalogMapper.WikiSnapshot snapshot = mappedSnapshot(
                "Epic_slime_sword.png",
                "Epicki Szlamowy Miecz",
                List.of("Kategoria:Przedmioty"));

        WikiCatalogMapper.MappingResult result = WikiCatalogMapper.map(catalog, snapshot, List.of());
        String yaml = catalog.render(result.decisionsByModelPath());

        assertEquals(1, result.mappedModelPaths());
        assertEquals(1, result.mappedRecords());
        assertTrue(yaml.contains("wiki: \"Epicki_Szlamowy_Miecz\""));
        assertTrue(yaml.contains("name: \"Epicki Szlamowy Miecz\""));
    }

    @Test
    void matchesImageFilenameCaseInsensitively() {
        WikiCatalogMapper.CatalogDocument catalog = catalog(record("diamond_sword", 1, "epic_slime_sword", 0));
        WikiCatalogMapper.WikiSnapshot snapshot = mappedSnapshot(
                "EPIC_SLIME_SWORD.PNG",
                "Epicki Szlamowy Miecz",
                List.of("Kategoria:Przedmioty"));

        WikiCatalogMapper.MappingResult result = WikiCatalogMapper.map(catalog, snapshot, List.of());

        assertTrue(result.decisionsByModelPath().get("models/epic_slime_sword").mapped());
    }

    @Test
    void appliesOneModelMappingToSeveralMaterials() {
        WikiCatalogMapper.CatalogDocument catalog = catalog(
                record("diamond_sword", 1, "shared_sword", 0),
                record("netherite_sword", 2, "shared_sword", 0));
        WikiCatalogMapper.WikiSnapshot snapshot = mappedSnapshot(
                "Shared_sword.png",
                "Wspólny Miecz",
                List.of("Kategoria:Przedmioty"));

        WikiCatalogMapper.MappingResult result = WikiCatalogMapper.map(catalog, snapshot, List.of());
        String yaml = catalog.render(result.decisionsByModelPath());

        assertEquals(1, result.mappedModelPaths());
        assertEquals(2, result.mappedRecords());
        assertEquals(2, occurrences(yaml, "wiki: \"Wspólny_Miecz\""));
        assertEquals(2, occurrences(yaml, "name: \"Wspólny Miecz\""));
    }

    @Test
    void missingImageGoesToManualReview() {
        WikiCatalogMapper.CatalogDocument catalog = catalog(record("echo_shard", 1, "missing_model", 0));
        WikiCatalogMapper.WikiSnapshot snapshot = new WikiCatalogMapper.WikiSnapshot();
        snapshot.markImageInventoryComplete();

        WikiCatalogMapper.MappingResult result = WikiCatalogMapper.map(catalog, snapshot, List.of());

        WikiCatalogMapper.MappingDecision decision = result.decisionsByModelPath().get("models/missing_model");
        assertFalse(decision.mapped());
        assertEquals("missing_image", decision.reason());
        assertEquals(1, result.manualReviewCount());
    }

    @Test
    void severalItemPagesUsingOneImageAreAmbiguous() {
        WikiCatalogMapper.CatalogDocument catalog = catalog(record("book", 1, "double_page", 0));
        WikiCatalogMapper.WikiSnapshot snapshot = new WikiCatalogMapper.WikiSnapshot();
        snapshot.markImageInventoryComplete();
        snapshot.addImages(List.of("Double_page.png"));
        snapshot.putFileUsage("Double_page.png", List.of(
                new WikiCatalogMapper.WikiUsage(0, "Pierwszy Przedmiot"),
                new WikiCatalogMapper.WikiUsage(0, "Drugi Przedmiot")));
        snapshot.putPage(new WikiCatalogMapper.WikiPage("Pierwszy Przedmiot", true, List.of("Kategoria:Przedmioty")));
        snapshot.putPage(new WikiCatalogMapper.WikiPage("Drugi Przedmiot", true, List.of("Kategoria:Przedmioty")));

        WikiCatalogMapper.MappingResult result = WikiCatalogMapper.map(catalog, snapshot, List.of());

        WikiCatalogMapper.MappingDecision decision = result.decisionsByModelPath().get("models/double_page");
        assertFalse(decision.mapped());
        assertEquals("ambiguous_page", decision.reason());
        assertEquals(1, result.ambiguousMatches());
    }

    @Test
    void rejectsPagesOutsideItemCategory() {
        WikiCatalogMapper.CatalogDocument catalog = catalog(record("egg", 1, "boss_icon", 0));
        WikiCatalogMapper.WikiSnapshot snapshot = mappedSnapshot(
                "Boss_icon.png",
                "Szlam Behemot",
                List.of("Kategoria:Moby", "Kategoria:Eventy"));

        WikiCatalogMapper.MappingResult result = WikiCatalogMapper.map(catalog, snapshot, List.of());

        WikiCatalogMapper.MappingDecision decision = result.decisionsByModelPath().get("models/boss_icon");
        assertFalse(decision.mapped());
        assertEquals("no_item_page", decision.reason());
    }

    @Test
    void renderingNeverChangesShards() {
        WikiCatalogMapper.CatalogDocument catalog = catalog(
                record("diamond_sword", 1, "mapped", 0),
                record("echo_shard", 2, "unmapped", 7));
        WikiCatalogMapper.WikiSnapshot snapshot = mappedSnapshot(
                "Mapped.png",
                "Zmapowany Przedmiot",
                List.of("Kategoria:Przedmioty"));

        WikiCatalogMapper.MappingResult result = WikiCatalogMapper.map(catalog, snapshot, List.of());
        WikiCatalogMapper.CatalogDocument rendered = WikiCatalogMapper.CatalogDocument.parse(
                catalog.render(result.decisionsByModelPath()));

        assertEquals(List.of(0, 7), rendered.records().stream().map(WikiCatalogMapper.CatalogRecord::shards).toList());
    }

    @Test
    void uncertainCasesKeepWikiAndNameEmpty() {
        WikiCatalogMapper.CatalogDocument catalog = catalog(record("book", 1, "uncertain", 0));
        WikiCatalogMapper.WikiSnapshot snapshot = new WikiCatalogMapper.WikiSnapshot();
        snapshot.markImageInventoryComplete();
        snapshot.addImages(List.of("Uncertain.png"));
        snapshot.putFileUsage("Uncertain.png", List.of(new WikiCatalogMapper.WikiUsage(0, "Lista Przedmiotów")));
        snapshot.putPage(new WikiCatalogMapper.WikiPage("Lista Przedmiotów", true, List.of("Kategoria:Wiki")));

        WikiCatalogMapper.MappingResult result = WikiCatalogMapper.map(catalog, snapshot, List.of());
        String yaml = catalog.render(result.decisionsByModelPath());

        assertTrue(yaml.contains("wiki: \"\""));
        assertTrue(yaml.contains("name: \"\""));
        assertFalse(result.decisionsByModelPath().get("models/uncertain").mapped());
    }

    @Test
    void rejectsItemCategoryPageThatCollectsSeveralCatalogImages() {
        WikiCatalogMapper.CatalogDocument catalog = catalog(
                record("diamond_sword", 1, "first_weapon", 0),
                record("netherite_sword", 2, "second_weapon", 0));
        WikiCatalogMapper.WikiSnapshot snapshot = new WikiCatalogMapper.WikiSnapshot();
        snapshot.markImageInventoryComplete();
        snapshot.addImages(List.of("First_weapon.png", "Second_weapon.png"));
        snapshot.putFileUsage("First_weapon.png", List.of(
                new WikiCatalogMapper.WikiUsage(0, "Broń sygnaturowa")));
        snapshot.putFileUsage("Second_weapon.png", List.of(
                new WikiCatalogMapper.WikiUsage(0, "Broń sygnaturowa")));
        snapshot.putPage(new WikiCatalogMapper.WikiPage(
                "Broń sygnaturowa", true, List.of("Kategoria:Przedmioty")));

        WikiCatalogMapper.MappingResult result = WikiCatalogMapper.map(catalog, snapshot, List.of());

        assertEquals("collection_page", result.decisionsByModelPath().get("models/first_weapon").reason());
        assertEquals("collection_page", result.decisionsByModelPath().get("models/second_weapon").reason());
        assertEquals(0, result.mappedRecords());
    }

    @Test
    void detectsBasenameCollisionForDifferentModelPaths() {
        WikiCatalogMapper.CatalogDocument catalog = catalog(
                record("echo_shard", 2351043, "bat", "hat/bat", 0, "", ""),
                record("iron_sword", 2350601, "bat", "swords/bat", 0, "", ""));
        WikiCatalogMapper.WikiSnapshot snapshot = mappedSnapshot(
                "Bat.png",
                "Kij Bejsbolowy",
                List.of("Kategoria:Przedmioty"));

        WikiCatalogMapper.MappingResult result = WikiCatalogMapper.map(catalog, snapshot, List.of());
        String yaml = catalog.render(result.decisionsByModelPath());
        String manualReview = WikiCatalogMapper.renderManualReview(result);

        assertEquals(1, result.basenameCollisions());
        assertEquals("BASENAME_COLLISION", result.decisionsByModelPath().get("hat/bat").reason());
        assertEquals("BASENAME_COLLISION", result.decisionsByModelPath().get("swords/bat").reason());
        assertEquals(2, occurrences(yaml, "wiki: \"\""));
        assertEquals(2, occurrences(yaml, "name: \"\""));
        assertTrue(manualReview.contains("basename: \"bat\""));
        assertTrue(manualReview.contains("- \"hat/bat\""));
        assertTrue(manualReview.contains("- \"swords/bat\""));
        assertTrue(manualReview.contains("reason: \"BASENAME_COLLISION\""));
    }

    @Test
    void basenameCollisionCannotInheritAnotherModelPathsMapping() {
        WikiCatalogMapper.CatalogDocument catalog = catalog(
                record("echo_shard", 1, "bat", "hat/bat", 0, "Stary_Kapelusz", "Stary Kapelusz"),
                record("iron_sword", 2, "bat", "swords/bat", 0, "Kij_Bejsbolowy", "Kij Bejsbolowy"));
        WikiCatalogMapper.WikiSnapshot snapshot = mappedSnapshot(
                "Bat.png",
                "Kij Bejsbolowy",
                List.of("Kategoria:Przedmioty"));

        WikiCatalogMapper.MappingResult result = WikiCatalogMapper.map(catalog, snapshot, List.of());
        String yaml = catalog.render(result.decisionsByModelPath());

        assertEquals(0, result.mappedRecords());
        assertFalse(yaml.contains("Kij_Bejsbolowy"));
        assertFalse(yaml.contains("Stary_Kapelusz"));
        assertEquals(2, occurrences(yaml, "wiki: \"\""));
        assertEquals(2, occurrences(yaml, "name: \"\""));
    }

    @Test
    void rerunClearsStaleWikiAndNameForUnmappedModelPath() {
        WikiCatalogMapper.CatalogDocument catalog = catalog(
                record("book", 1, "missing", "books/missing", 0, "Stara_Strona", "Stara Strona"));
        WikiCatalogMapper.WikiSnapshot snapshot = new WikiCatalogMapper.WikiSnapshot();
        snapshot.markImageInventoryComplete();

        WikiCatalogMapper.MappingResult result = WikiCatalogMapper.map(catalog, snapshot, List.of());
        String yaml = catalog.render(result.decisionsByModelPath());

        assertTrue(yaml.contains("wiki: \"\""));
        assertTrue(yaml.contains("name: \"\""));
        assertFalse(yaml.contains("Stara_Strona"));
        assertFalse(yaml.contains("Stara Strona"));
    }

    private static WikiCatalogMapper.WikiSnapshot mappedSnapshot(
            String image,
            String page,
            List<String> categories
    ) {
        WikiCatalogMapper.WikiSnapshot snapshot = new WikiCatalogMapper.WikiSnapshot();
        snapshot.markImageInventoryComplete();
        snapshot.addImages(List.of(image));
        snapshot.putFileUsage(image, List.of(new WikiCatalogMapper.WikiUsage(0, page)));
        snapshot.putPage(new WikiCatalogMapper.WikiPage(page, true, categories));
        return snapshot;
    }

    private static WikiCatalogMapper.CatalogDocument catalog(String... records) {
        return WikiCatalogMapper.CatalogDocument.parse("items:\n" + String.join("", records));
    }

    private static String record(String material, int cmd, String model, int shards) {
        return record(material, cmd, model, "models/" + model, shards, "", "");
    }

    private static String record(
            String material,
            int cmd,
            String model,
            String modelPath,
            int shards,
            String wiki,
            String name
    ) {
        return """
                  "%s:%d":
                    material: "%s"
                    cmd: %d
                    model: "%s"
                    model_path: "%s"
                    type: "misc"
                    wiki: "%s"
                    name: "%s"
                    shards: %d
                """.formatted(material, cmd, material, cmd, model, modelPath, wiki, name, shards);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
