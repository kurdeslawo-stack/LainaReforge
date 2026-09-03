package pl.laina.reforge.rules;

/** Short player/admin-safe text; logic never depends on these messages. */
public final class RecyclingReasonText {

    private RecyclingReasonText() {
    }

    public static String describe(RecyclingDecision decision) {
        return switch (decision.reasonCode()) {
            case ALLOWED_EXPLICIT_ITEM -> "Jawnie dozwolony dla tego itemu.";
            case ALLOWED_CATEGORY -> "Dozwolony przez polityke kategorii.";
            case ALLOWED_APPROVED_DECISION -> "Dozwolony przez zatwierdzona decyzje recyclingowa.";
            case BLOCKED_NO_ITEM -> "Brak odpowiedniego itemu.";
            case BLOCKED_PLUGIN_CURRENCY -> "Waluta LainaReforge nigdy nie podlega recyklingowi.";
            case BLOCKED_UNRECOGNIZED -> "Brak wspieranego, stabilnego ID customu.";
            case BLOCKED_PENDING_CLASSIFICATION -> "Custom wymaga klasyfikacji w Discovery Queue.";
            case BLOCKED_BLACKLISTED_ID -> "ID znajduje sie na twardej liscie blokad.";
            case BLOCKED_EXPLICIT_ITEM -> "Recykling jest jawnie wylaczony dla tego itemu.";
            case BLOCKED_CATEGORY -> "Kategoria znajduje sie na twardej liscie blokad.";
            case BLOCKED_CATEGORY_POLICY -> "Polityka kategorii nie pozwala na recykling.";
            case BLOCKED_MISSING_VALUE -> "Brak bezpiecznej dodatniej wartosci w odlamkach.";
            case BLOCKED_INVALID_CONFIGURATION -> "Aktywna konfiguracja regul jest niepoprawna.";
            case BLOCKED_APPROVED_DECISION_REJECTED -> "Recykling zostal odrzucony w zatwierdzonych decyzjach.";
            case BLOCKED_APPROVED_DECISION_NOT_CONFIGURED -> "Brak zatwierdzonej decyzji dla tego materialu i CMD.";
            case BLOCKED_INVALID_IDENTITY -> "Przedmiot nie ma poprawnej kombinacji materialu i CMD.";
            case BLOCKED_REWARD_LIMIT -> "Laczna nagroda przekracza techniczny limit bezpieczenstwa.";
            case BLOCKED_REWARD_OVERFLOW -> "Laczna nagroda przekracza bezpieczny zakres.";
        };
    }
}
