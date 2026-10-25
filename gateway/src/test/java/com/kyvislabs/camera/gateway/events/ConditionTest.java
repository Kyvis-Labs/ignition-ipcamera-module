package com.kyvislabs.camera.gateway.events;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConditionTest {
    @Test
    void blankAndTrueAreTruthy() {
        for (String c : new String[]{null, "", "  ", "true", "TRUE"}) {
            Condition k = Condition.parse(c);
            assertTrue(k.test(true));
            assertTrue(k.test(3));
            assertFalse(k.test(false));
            assertFalse(k.test(0));
            assertFalse(k.test(null));
        }
    }

    @Test
    void falseMatchesOnlyNonNullFalsy() {
        Condition k = Condition.parse("false");
        assertTrue(k.test(false));
        assertTrue(k.test(0));
        assertFalse(k.test(true));
        assertFalse(k.test(null));
    }

    @Test
    void numericComparisons() {
        assertTrue(Condition.parse("> 5").test(6));
        assertFalse(Condition.parse("> 5").test(5));
        assertTrue(Condition.parse(">=5").test(5.0));
        assertTrue(Condition.parse("< 1.5").test("1.2"));
        assertTrue(Condition.parse("<= 0").test(false));
        assertFalse(Condition.parse("> 5").test("abc"));
    }

    @Test
    void equality() {
        assertTrue(Condition.parse("= 3").test(3.0));
        assertTrue(Condition.parse("== 'Open'").test("Open"));
        assertFalse(Condition.parse("= Open").test("Closed"));
        assertTrue(Condition.parse("!= Open").test("Closed"));
        assertFalse(Condition.parse("!= 1").test(true));
    }

    @Test
    void garbageIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("between 1 and 2"));
    }
}
