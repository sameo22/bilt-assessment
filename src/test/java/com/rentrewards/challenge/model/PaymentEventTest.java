package com.rentrewards.challenge.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentEventTest {

    @Test
    void rejectsNegativeAmounts() {
        assertThrows(IllegalArgumentException.class, () -> paymentOf("-500"));
    }

    @Test
    void rejectsZeroAmounts() {
        assertThrows(IllegalArgumentException.class, () -> paymentOf("0"));
    }

    private static PaymentEvent paymentOf(String amount) {
        return new PaymentEvent("evt-1", "member-1", new BigDecimal(amount),
                false, LocalDate.of(2026, 3, 1));
    }
}
