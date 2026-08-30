package pl.laina.reforge.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.laina.reforge.LainaReforgePlugin;
import pl.laina.reforge.rules.RecyclingDecision;
import pl.laina.reforge.rules.RecyclingReasonCode;
import pl.laina.reforge.rules.RecyclingReasonText;
import pl.laina.reforge.rules.RecyclingRulesEngine;
import pl.laina.reforge.rules.RecyclingTransactionValidator;
import pl.laina.reforge.service.CurrencyService;
import pl.laina.reforge.service.PendingItemService;
import pl.laina.reforge.service.TransactionLogService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RecyclerMenu {

    public static final int SIZE = 54;
    public static final int INFO_SLOT = 4;
    public static final int PREVIEW_SLOT = 25;
    public static final int STATUS_SLOT = 34;
    public static final int CANCEL_SLOT = 48;
    public static final int CONFIRM_SLOT = 50;

    private static final List<Integer> INPUT_SLOTS = List.of(
            10, 11, 12, 13, 14,
            19, 20, 21, 22, 23,
            28, 29, 30, 31, 32,
            37, 38, 39, 40, 41
    );
    private static final Set<Integer> INPUT_SLOT_SET = Set.copyOf(INPUT_SLOTS);
    private static final int MAX_VISIBLE_ISSUES = 3;
    private static final long INVALID_FEEDBACK_COOLDOWN_MILLIS = 1500L;

    private final LainaReforgePlugin plugin;
    private final RecyclingRulesEngine rulesEngine;
    private final CurrencyService currencyService;
    private final TransactionLogService transactionLogService;
    private final PendingItemService pendingItemService;
    private final RecyclingTransactionValidator transactionValidator = new RecyclingTransactionValidator();
    private final Map<UUID, InvalidFeedback> invalidFeedbackByPlayer = new HashMap<>();
    private final Set<RecyclerHolder> pendingPreviewRefreshes =
            Collections.newSetFromMap(new IdentityHashMap<>());

    public RecyclerMenu(LainaReforgePlugin plugin,
                        RecyclingRulesEngine rulesEngine,
                        CurrencyService currencyService,
                        TransactionLogService transactionLogService,
                        PendingItemService pendingItemService) {
        this.plugin = plugin;
        this.rulesEngine = rulesEngine;
        this.currencyService = currencyService;
        this.transactionLogService = transactionLogService;
        this.pendingItemService = pendingItemService;
    }

    public void open(Player player) {
        RecyclerHolder holder = new RecyclerHolder();
        String title = plugin.getConfig().getString("gui.title", "Recykler • LainaReforge");
        Inventory inventory = Bukkit.createInventory(holder, SIZE, Component.text(title));
        holder.attach(inventory);
        decorate(inventory);
        player.openInventory(inventory);
    }

    public void decorate(Inventory inventory) {
        ItemStack background = item(Material.BLACK_STAINED_GLASS_PANE, " ", NamedTextColor.BLACK, List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, background);
        }
        for (int slot : INPUT_SLOTS) {
            inventory.setItem(slot, null);
        }
        ItemStack divider = item(Material.PURPLE_STAINED_GLASS_PANE, " ", NamedTextColor.DARK_PURPLE, List.of());
        for (int slot : List.of(3, 5, 15, 24, 33, 42, 45, 53)) {
            inventory.setItem(slot, divider);
        }
        inventory.setItem(INFO_SLOT, informationItem());
        inventory.setItem(CANCEL_SLOT, cancelItem());
        updatePreview(inventory);
    }

    public void queuePreviewRefresh(Inventory inventory) {
        if (!(inventory.getHolder() instanceof RecyclerHolder holder)
                || !holder.isActive()
                || !pendingPreviewRefreshes.add(holder)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            pendingPreviewRefreshes.remove(holder);
            if (holder.isActive() && inventory.getHolder() == holder) {
                updatePreview(inventory);
            }
        });
    }

    public void updatePreview(Inventory inventory) {
        RecycleAnalysis analysis = analyze(inventory);
        inventory.setItem(PREVIEW_SLOT, previewItem(analysis));
        inventory.setItem(STATUS_SLOT, statusItem(analysis));
        inventory.setItem(CONFIRM_SLOT, confirmItem(analysis));
    }

    public RecycleResult calculate(Inventory inventory) {
        return analyze(inventory).result();
    }

    public boolean confirm(Player player, Inventory inventory) {
        // This is intentionally a fresh pass. Preview state is never trusted for destruction or payout.
        RecycleAnalysis finalAnalysis = analyze(inventory);
        boolean discovered = recordPending(player, finalAnalysis);
        RecycleResult result = finalAnalysis.result();

        if (!finalAnalysis.issues().isEmpty()) {
            String signature = String.join("|", result.invalidItems());
            if (shouldSendInvalidFeedback(player, signature)) {
                sendProblemFeedback(player, finalAnalysis, discovered);
            }
            updatePreview(inventory);
            return false;
        }

        invalidFeedbackByPlayer.remove(player.getUniqueId());
        if (!isTransactionPossible(result)) {
            player.sendMessage(text("Dodaj przedmioty, które chcesz przetopić.", NamedTextColor.RED));
            updatePreview(inventory);
            return false;
        }

        // All decisions are valid at this point in the same server tick. Mutations begin only after
        // the all-or-nothing validator has produced a complete payout plan.
        for (int slot : INPUT_SLOTS) {
            inventory.setItem(slot, null);
        }
        currencyService.giveShards(player, result.totalShards());
        transactionLogService.logRecycle(player, result.itemAmounts(), result.totalShards());
        player.sendMessage(text("Przetopienie zakończone. Przedmioty: " + acceptedItemCount(result)
                + ". Odłamki Customu: " + result.totalShards() + ".", NamedTextColor.GREEN));
        player.closeInventory();
        return true;
    }

    public void returnItems(Player player, Inventory inventory) {
        if (inventory.getHolder() instanceof RecyclerHolder holder) {
            holder.markClosed();
            pendingPreviewRefreshes.remove(holder);
        }
        invalidFeedbackByPlayer.remove(player.getUniqueId());
        for (int slot : INPUT_SLOTS) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            inventory.setItem(slot, null);
            player.getInventory().addItem(item).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
    }

    public int closeOpenMenus() {
        int closedMenus = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory inventory = player.getOpenInventory().getTopInventory();
            if (!(inventory.getHolder() instanceof RecyclerHolder)) {
                continue;
            }
            returnItems(player, inventory);
            player.closeInventory();
            closedMenus++;
        }
        pendingPreviewRefreshes.clear();
        invalidFeedbackByPlayer.clear();
        return closedMenus;
    }

    public static boolean isInputSlot(int rawSlot) {
        return INPUT_SLOT_SET.contains(rawSlot);
    }

    static List<Integer> inputSlots() {
        return INPUT_SLOTS;
    }

    static boolean isTransactionPossible(RecycleResult result) {
        return result != null
                && result.totalShards() > 0
                && !result.itemAmounts().isEmpty()
                && result.invalidItems().isEmpty();
    }

    static int acceptedItemCount(RecycleResult result) {
        long count = result.itemAmounts().values().stream().mapToLong(Integer::longValue).sum();
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    private RecycleAnalysis analyze(Inventory inventory) {
        int acceptedStacks = 0;
        Map<IssueKey, MutableIssue> issues = new LinkedHashMap<>();
        List<RecyclingTransactionValidator.EvaluatedStack> evaluated = new ArrayList<>();
        List<PendingCandidate> pending = new ArrayList<>();

        for (int slot : INPUT_SLOTS) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            RecyclingDecision decision = rulesEngine.evaluate(item);
            evaluated.add(new RecyclingTransactionValidator.EvaluatedStack(item.getAmount(), decision));
            if (decision.recyclable()) {
                acceptedStacks++;
                continue;
            }

            IssueType type = issueType(decision.reasonCode());
            String reference = decision.technicalId().isBlank()
                    ? "Typ: " + item.getType().getKey()
                    : "ID: " + decision.technicalId();
            addIssue(issues, type, displayName(item), reference,
                    RecyclingReasonText.describe(decision), decision.reasonCode(), item.getAmount());
            if (decision.requiresClassification()) {
                pending.add(new PendingCandidate(item, decision.technicalId()));
            }
        }

        RecyclingTransactionValidator.TransactionPlan plan = transactionValidator.validate(evaluated);
        List<RecycleIssue> immutableIssues = issues.values().stream().map(MutableIssue::toIssue).toList();
        List<String> invalidItems = immutableIssues.stream()
                .map(issue -> issue.reasonCode() + "|" + issue.technicalReference() + "|" + issue.amount())
                .toList();
        RecycleResult result = new RecycleResult(
                plan.totalShards(), acceptedStacks, invalidItems, plan.itemAmounts());
        return new RecycleAnalysis(result, immutableIssues, List.copyOf(pending));
    }

    private IssueType issueType(RecyclingReasonCode code) {
        return switch (code) {
            case BLOCKED_PLUGIN_CURRENCY -> IssueType.PROTECTED_CURRENCY;
            case BLOCKED_UNRECOGNIZED, BLOCKED_NO_ITEM -> IssueType.UNIDENTIFIED;
            case BLOCKED_PENDING_CLASSIFICATION -> IssueType.DISCOVERY_PENDING;
            default -> IssueType.BLOCKED;
        };
    }

    private void addIssue(Map<IssueKey, MutableIssue> issues,
                          IssueType type,
                          Component displayName,
                          String technicalReference,
                          String reason,
                          RecyclingReasonCode reasonCode,
                          int amount) {
        IssueKey key = new IssueKey(type, displayName, technicalReference, reasonCode);
        issues.compute(key, (ignored, current) -> current == null
                ? new MutableIssue(type, displayName, technicalReference, reason, reasonCode, amount)
                : current.withAdditionalAmount(amount));
    }

    private boolean recordPending(Player player, RecycleAnalysis analysis) {
        if (!pendingItemService.isEnabled()) {
            return false;
        }
        boolean found = false;
        for (PendingCandidate candidate : analysis.pendingCandidates()) {
            found = true;
            pendingItemService.record(player, candidate.technicalId(), candidate.item());
        }
        return found;
    }

    private void sendProblemFeedback(Player player, RecycleAnalysis analysis, boolean discovered) {
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        player.sendMessage(Component.text(stripLegacy(prefix))
                .append(text("Transakcja wymaga poprawy:", NamedTextColor.RED)));
        for (RecycleIssue issue : analysis.issues()) {
            player.sendMessage(text("• ", NamedTextColor.RED)
                    .append(issue.displayName())
                    .append(text(" ×" + issue.amount() + " — " + issue.reason(), NamedTextColor.RED)));
        }
        if (discovered) {
            player.sendMessage(text("Nieznany custom został zachowany i zapisany w Discovery Queue.",
                    NamedTextColor.YELLOW));
        }
    }

    private boolean shouldSendInvalidFeedback(Player player, String signature) {
        long now = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();
        InvalidFeedback previous = invalidFeedbackByPlayer.get(playerId);
        if (previous != null && previous.signature().equals(signature)
                && now - previous.timestampMillis() < INVALID_FEEDBACK_COOLDOWN_MILLIS) {
            return false;
        }
        invalidFeedbackByPlayer.put(playerId, new InvalidFeedback(signature, now));
        return true;
    }

    private ItemStack informationItem() {
        return item(Material.BOOK, "Jak działa recycler", NamedTextColor.AQUA, List.of(
                text("Umieść przedmioty po lewej.", NamedTextColor.GRAY),
                text("Po prawej sprawdzisz wynik.", NamedTextColor.GRAY),
                Component.empty(),
                text("Problem wstrzyma całą transakcję.", NamedTextColor.YELLOW),
                text("Anulowanie zwraca zawartość.", NamedTextColor.YELLOW)
        ));
    }

    private ItemStack previewItem(RecycleAnalysis analysis) {
        RecycleResult result = analysis.result();
        int accepted = acceptedItemCount(result);
        int problems = problemItemCount(analysis.issues());
        List<Component> lore = new ArrayList<>();
        lore.add(text("Przyjęte: " + accepted, accepted > 0 ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        lore.add(text("Odłamki Customu: " + result.totalShards(),
                result.totalShards() > 0 ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GRAY));
        lore.add(text("Wymagają uwagi: " + problems, problems > 0 ? NamedTextColor.RED : NamedTextColor.GRAY));
        lore.add(Component.empty());
        if (isTransactionPossible(result)) {
            lore.add(text("Gotowe do przetopienia.", NamedTextColor.GREEN));
        } else if (!analysis.issues().isEmpty()) {
            lore.add(text("Sprawdź diagnostykę poniżej.", NamedTextColor.RED));
        } else {
            lore.add(text("Umieść przedmioty po lewej.", NamedTextColor.GRAY));
        }
        ItemStack preview = item(Material.AMETHYST_SHARD, "Podgląd nagrody", NamedTextColor.LIGHT_PURPLE, lore);
        preview.setAmount(Math.max(1, Math.min(64, result.totalShards())));
        return preview;
    }

    private ItemStack statusItem(RecycleAnalysis analysis) {
        if (analysis.result().itemAmounts().isEmpty() && analysis.issues().isEmpty()) {
            return item(Material.HOPPER, "Oczekiwanie na przedmioty", NamedTextColor.AQUA,
                    List.of(text("Wolne pola znajdują się po lewej.", NamedTextColor.GRAY)));
        }
        if (analysis.issues().isEmpty()) {
            return item(Material.LIME_DYE, "Wszystko gotowe", NamedTextColor.GREEN, List.of(
                    text("Nie wykryto żadnych problemów.", NamedTextColor.GRAY),
                    text("Możesz zatwierdzić przetopienie.", NamedTextColor.GREEN)));
        }

        List<Component> lore = new ArrayList<>();
        lore.add(text("Transakcja jest wstrzymana.", NamedTextColor.RED));
        lore.add(text("Żaden przedmiot nie zostanie zniszczony.", NamedTextColor.YELLOW));
        lore.add(Component.empty());
        int shown = 0;
        for (RecycleIssue issue : analysis.issues()) {
            if (shown >= MAX_VISIBLE_ISSUES) {
                break;
            }
            lore.add(text("• ", NamedTextColor.RED).append(issue.displayName())
                    .append(text(" ×" + issue.amount(), NamedTextColor.RED)));
            lore.add(text("  " + issue.reason(), NamedTextColor.GRAY));
            if (!issue.technicalReference().isBlank()) {
                lore.add(text("  " + issue.technicalReference(), NamedTextColor.DARK_GRAY));
            }
            if (issue.type() == IssueType.DISCOVERY_PENDING && pendingItemService.isEnabled()) {
                lore.add(text("  Wykryty przez Discovery Queue.", NamedTextColor.YELLOW));
            }
            shown++;
        }
        if (analysis.issues().size() > shown) {
            lore.add(text("…oraz " + (analysis.issues().size() - shown) + " kolejne problemy.", NamedTextColor.RED));
        }
        return item(Material.RED_DYE, "Wymagana uwaga", NamedTextColor.RED, lore);
    }

    private ItemStack confirmItem(RecycleAnalysis analysis) {
        RecycleResult result = analysis.result();
        if (isTransactionPossible(result)) {
            return item(Material.LIME_CONCRETE, "Przetop przedmioty", NamedTextColor.GREEN, List.of(
                    text("Przedmioty: " + acceptedItemCount(result), NamedTextColor.GRAY),
                    text("Odłamki Customu: " + result.totalShards(), NamedTextColor.LIGHT_PURPLE),
                    Component.empty(), text("Kliknij, aby zatwierdzić.", NamedTextColor.GREEN)));
        }
        if (!analysis.issues().isEmpty()) {
            return item(Material.RED_CONCRETE, "Nie można zatwierdzić", NamedTextColor.RED, List.of(
                    text("Najpierw usuń problematyczne", NamedTextColor.GRAY),
                    text("przedmioty wskazane po prawej.", NamedTextColor.GRAY)));
        }
        return item(Material.GRAY_CONCRETE, "Brak przedmiotów", NamedTextColor.GRAY, List.of(
                text("Przycisk uaktywni się, gdy", NamedTextColor.DARK_GRAY),
                text("dodasz poprawne przedmioty.", NamedTextColor.DARK_GRAY)));
    }

    private ItemStack cancelItem() {
        return item(Material.BARRIER, "Anuluj", NamedTextColor.RED, List.of(
                text("Zamknij menu bez przetapiania.", NamedTextColor.GRAY),
                text("Wszystkie przedmioty zostaną zwrócone.", NamedTextColor.YELLOW)));
    }

    private ItemStack item(Material material, String name, NamedTextColor color, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text(name, color));
        if (!lore.isEmpty()) {
            meta.lore(lore.stream().map(this::withoutItalics).toList());
        }
        item.setItemMeta(meta);
        return item;
    }

    private Component text(String value, NamedTextColor color) {
        return withoutItalics(Component.text(value, color));
    }

    private Component displayName(ItemStack item) {
        return withoutItalics(item.effectiveName());
    }

    private Component withoutItalics(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private int problemItemCount(List<RecycleIssue> issues) {
        long count = issues.stream().mapToLong(RecycleIssue::amount).sum();
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    private String stripLegacy(String value) {
        return value == null ? "" : value.replaceAll("(?i)&[0-9A-FK-ORX]", "");
    }

    private enum IssueType {
        PROTECTED_CURRENCY,
        UNIDENTIFIED,
        DISCOVERY_PENDING,
        BLOCKED
    }

    private record IssueKey(IssueType type, Component displayName, String technicalReference,
                            RecyclingReasonCode reasonCode) {
    }

    private record RecycleIssue(IssueType type, Component displayName, String technicalReference,
                                String reason, RecyclingReasonCode reasonCode, int amount) {
    }

    private record MutableIssue(IssueType type, Component displayName, String technicalReference,
                                String reason, RecyclingReasonCode reasonCode, int amount) {
        private MutableIssue withAdditionalAmount(int additionalAmount) {
            long sum = (long) amount + additionalAmount;
            int safeAmount = sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
            return new MutableIssue(type, displayName, technicalReference, reason, reasonCode, safeAmount);
        }

        private RecycleIssue toIssue() {
            return new RecycleIssue(type, displayName, technicalReference, reason, reasonCode, amount);
        }
    }

    private record PendingCandidate(ItemStack item, String technicalId) {
    }

    private record RecycleAnalysis(RecycleResult result,
                                   List<RecycleIssue> issues,
                                   List<PendingCandidate> pendingCandidates) {
    }

    private record InvalidFeedback(String signature, long timestampMillis) {
    }

    public record RecycleResult(int totalShards,
                                int stacks,
                                List<String> invalidItems,
                                Map<String, Integer> itemAmounts) {
    }
}
