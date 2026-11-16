package com.rentrewards.challenge.service;

import com.rentrewards.challenge.model.MemberAccount;
import com.rentrewards.challenge.model.PaymentEvent;
import com.rentrewards.challenge.model.PointsResult;
import com.rentrewards.challenge.model.ProcessingOutcome;

import java.time.YearMonth;

/**
 * Orchestrates the processing of an incoming payment webhook event:
 *
 *  1. Claim the eventId. If someone already has it, it's a duplicate, skip it.
 *  2. Calculate base points (with linked-account multiplier).
 *  3. Apply streak bonus if the member is eligible.
 *  4. Record the points, without going over the monthly cap.
 *
 * We claim first and award after (instead of check, award, then mark), so
 * if the same event hits two workers at once only one of them gets through.
 * If something fails before the points are saved we drop the claim, so the
 * processor's retry doesn't get wrongly skipped as a duplicate.
 */
public class RewardsEngine {

    private static final long MONTHLY_POINTS_CAP = 100_000L;

    private final PointsCalculator pointsCalculator;
    private final ProcessedEventStore processedEventStore;

    public RewardsEngine(PointsCalculator pointsCalculator, ProcessedEventStore processedEventStore) {
        this.pointsCalculator = pointsCalculator;
        this.processedEventStore = processedEventStore;
    }

    public PointsResult processPayment(PaymentEvent event, MemberAccount member) {
        String eventId = event.getEventId();
        if (!processedEventStore.tryClaim(eventId)) {
            return new PointsResult(member.getMemberId(), 0, ProcessingOutcome.DUPLICATE);
        }

        try {
            return awardPoints(event, member);
        } catch (RuntimeException | Error e) {
            processedEventStore.release(eventId);
            throw e;
        }
    }

    private PointsResult awardPoints(PaymentEvent event, MemberAccount member) {
        long basePoints = pointsCalculator.calculateBasePoints(event);
        long pointsWithBonus = pointsCalculator.applyStreakBonusIfEligible(
                basePoints, member.getCurrentStreakMonths());

        YearMonth month = YearMonth.from(event.getPaymentDate());
        long pointsToAward = member.addPointsUpToCap(month, pointsWithBonus, MONTHLY_POINTS_CAP);

        // A sub-dollar payment earns 0 points on its own, that's not the cap.
        boolean blockedByCap = pointsToAward == 0 && pointsWithBonus > 0;
        ProcessingOutcome outcome = blockedByCap
                ? ProcessingOutcome.CAPPED
                : ProcessingOutcome.AWARDED;
        return new PointsResult(member.getMemberId(), pointsToAward, outcome);
    }
}
