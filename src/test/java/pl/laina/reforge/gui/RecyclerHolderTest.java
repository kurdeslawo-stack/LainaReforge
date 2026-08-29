package pl.laina.reforge.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecyclerHolderTest {

    @Test
    void holderStopsAcceptingRefreshesAfterMenuIsClosed() {
        RecyclerHolder holder = new RecyclerHolder();

        assertTrue(holder.isActive());

        holder.markClosed();
        holder.markClosed();

        assertFalse(holder.isActive());
    }
}
