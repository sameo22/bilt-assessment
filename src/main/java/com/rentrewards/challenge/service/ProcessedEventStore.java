package com.rentrewards.challenge.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which webhook events have already been processed, so that the
 * RewardsEngine can ignore duplicate deliveries from the payment processor.
 *
 * We keep every eventId we've seen (not just the last one), so a retry gets
 * caught even if other events came in before it. Claiming is one atomic
 * add(), so if the same event hits several workers at once, only one wins.
 */
public class ProcessedEventStore {

    private final Set<String> claimedEventIds = ConcurrentHashMap.newKeySet();

    /**
     * Tries to grab this event for processing.
     *
     * @return true if it's ours to process, false if someone already got it
     */
    public boolean tryClaim(String eventId) {
        return claimedEventIds.add(eventId);
    }

    /**
     * Lets go of an event if something blew up before we recorded any points,
     * so the processor's next retry can still go through.
     */
    public void release(String eventId) {
        claimedEventIds.remove(eventId);
    }
}
