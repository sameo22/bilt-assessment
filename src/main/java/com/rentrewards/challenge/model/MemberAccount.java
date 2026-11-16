package com.rentrewards.challenge.model;

import java.time.YearMonth;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal in-memory representation of a member's rewards account.
 * In production this would be backed by a database; for this challenge
 * an in-memory model is enough to exercise the business rules.
 */
public class MemberAccount {

    private final String memberId;
    private int currentStreakMonths;
    private final Map<YearMonth, Long> pointsByMonth = new ConcurrentHashMap<>();

    public MemberAccount(String memberId, int currentStreakMonths) {
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.currentStreakMonths = currentStreakMonths;
    }

    public String getMemberId() {
        return memberId;
    }

    public int getCurrentStreakMonths() {
        return currentStreakMonths;
    }

    public long getPointsForMonth(YearMonth month) {
        return pointsByMonth.getOrDefault(month, 0L);
    }

    /**
     * Adds points for the month but never past the cap. It's synchronized so
     * the read and the add happen together, otherwise two events landing at
     * the same time could both sneak in under the cap.
     *
     * @return how many points actually got added (0 if already at the cap).
     */
    public synchronized long addPointsUpToCap(YearMonth month, long requestedPoints, long monthlyCap) {
        long earned = getPointsForMonth(month);
        long added = Math.min(requestedPoints, Math.max(0L, monthlyCap - earned));
        pointsByMonth.merge(month, added, Long::sum);
        return added;
    }
}
