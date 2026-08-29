package pl.laina.reforge.gui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecyclerMenuTest {

    @Test
    void inputAreaContainsTwentyUniqueSlotsInsideMenu() {
        List<Integer> slots = RecyclerMenu.inputSlots();

        assertEquals(20, slots.size());
        assertEquals(slots.size(), Set.copyOf(slots).size());
        assertTrue(slots.stream().allMatch(slot -> slot >= 0 && slot < RecyclerMenu.SIZE));
    }

    @Test
    void interfaceControlsNeverOverlapInputArea() {
        assertFalse(RecyclerMenu.isInputSlot(RecyclerMenu.INFO_SLOT));
        assertFalse(RecyclerMenu.isInputSlot(RecyclerMenu.PREVIEW_SLOT));
        assertFalse(RecyclerMenu.isInputSlot(RecyclerMenu.STATUS_SLOT));
        assertFalse(RecyclerMenu.isInputSlot(RecyclerMenu.CONFIRM_SLOT));
        assertFalse(RecyclerMenu.isInputSlot(RecyclerMenu.CANCEL_SLOT));
    }

    @Test
    void transactionRequiresRewardAcceptedItemsAndNoProblems() {
        RecyclerMenu.RecycleResult valid = new RecyclerMenu.RecycleResult(
                8, 1, List.of(), Map.of("example_rare_item", 2));
        RecyclerMenu.RecycleResult empty = new RecyclerMenu.RecycleResult(
                0, 0, List.of(), Map.of());
        RecyclerMenu.RecycleResult blocked = new RecyclerMenu.RecycleResult(
                8, 1, List.of("example_manual_block — blokada"), Map.of("example_rare_item", 2));

        assertTrue(RecyclerMenu.isTransactionPossible(valid));
        assertFalse(RecyclerMenu.isTransactionPossible(empty));
        assertFalse(RecyclerMenu.isTransactionPossible(blocked));
    }

    @Test
    void acceptedItemCountSumsAmountsAcrossCustomTypes() {
        RecyclerMenu.RecycleResult result = new RecyclerMenu.RecycleResult(
                20, 3, List.of(), Map.of("first", 2, "second", 3));

        assertEquals(5, RecyclerMenu.acceptedItemCount(result));
    }
}
