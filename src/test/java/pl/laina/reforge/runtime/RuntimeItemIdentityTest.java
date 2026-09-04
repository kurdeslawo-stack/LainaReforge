package pl.laina.reforge.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeItemIdentityTest {
    @Test
    void acceptsRealMaterialAndNormalizesCase() {
        assertEquals("diamond_sword:123", new RuntimeItemIdentity("DIAMOND_SWORD", 123).key());
    }

    @Test
    void rejectsUnknownMaterialAndNonPositiveCmd() {
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeItemIdentity("not_a_real_material", 123));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeItemIdentity("stone", 0));
    }
}
