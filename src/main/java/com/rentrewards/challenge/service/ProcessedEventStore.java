package com.rentrewards.challenge.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which webhook events have already been processed, so that the
 * RewardsEngine can ignore duplicate deliveries from the payment processor.
 */
public class ProcessedEventStore {

    private final Set<String> claimedEventIds = ConcurrentHashMap.newKeySet();

    /** Atomically claims the event; false means it's a duplicate. */
    public boolean tryClaim(String eventId) {
        return claimedEventIds.add(eventId);
    }

    /** Frees a claim when processing failed, so the retry can go through. */
    public void release(String eventId) {
        claimedEventIds.remove(eventId);
    }
}
