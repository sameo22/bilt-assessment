package com.rentrewards.challenge.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class MemberAccountTest {

    @Test
    void rejectsANullMemberId() {
        assertThrows(NullPointerException.class, () -> new MemberAccount(null, 0));
    }
}
