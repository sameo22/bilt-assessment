package com.rentrewards.challenge.service;

import com.rentrewards.challenge.model.MemberAccount;
import com.rentrewards.challenge.model.PaymentEvent;
import com.rentrewards.challenge.model.PointsResult;
import com.rentrewards.challenge.model.ProcessingOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewardsEngineTest {

    private static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);
    private static final YearMonth MARCH = YearMonth.of(2026, 3);

    private RewardsEngine engine;

    @BeforeEach
    void setUp() {
        engine = new RewardsEngine(new PointsCalculator(), new ProcessedEventStore());
    }

    @Test
    void awardsOnePointPerDollarByDefault() {
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent event = new PaymentEvent("evt-1", "member-1",
                new BigDecimal("1500"), false, LocalDate.of(2026, 3, 1));

        PointsResult result = engine.processPayment(event, member);

        assertEquals(1500, result.getPointsAwarded());
        assertEquals(ProcessingOutcome.AWARDED, result.getOutcome());
        assertFalse(result.isSkippedAsDuplicate());
    }

    @Test
    void appliesLinkedAccountMultiplier() {
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent event = new PaymentEvent("evt-1", "member-1",
                new BigDecimal("1500"), true, LocalDate.of(2026, 3, 1));

        PointsResult result = engine.processPayment(event, member);

        assertEquals(3000, result.getPointsAwarded());
        assertEquals(ProcessingOutcome.AWARDED, result.getOutcome());
    }

    @Test
    void appliesStreakBonusWhenEligible() {
        MemberAccount member = new MemberAccount("member-1", 6);
        PaymentEvent event = new PaymentEvent("evt-1", "member-1",
                new BigDecimal("2000"), true, LocalDate.of(2026, 3, 1));

        // base = 2000 * 2 (linked) = 4000; +10% streak bonus = 4400
        PointsResult result = engine.processPayment(event, member);

        assertEquals(4400, result.getPointsAwarded());
        assertEquals(ProcessingOutcome.AWARDED, result.getOutcome());
    }

    @Test
    void enforcesMonthlyPointsCap() {
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent event = new PaymentEvent("evt-1", "member-1",
                new BigDecimal("150000"), false, LocalDate.of(2026, 3, 1));

        PointsResult result = engine.processPayment(event, member);

        assertEquals(100_000, result.getPointsAwarded());
        assertEquals(ProcessingOutcome.AWARDED, result.getOutcome());
    }

    @Test
    void reportsCappedWhenALaterEventCannotAwardMorePoints() {
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent first = new PaymentEvent("evt-1", "member-1",
                new BigDecimal("150000"), false, LocalDate.of(2026, 3, 1));
        PaymentEvent second = new PaymentEvent("evt-2", "member-1",
                new BigDecimal("50"), false, LocalDate.of(2026, 3, 2));

        engine.processPayment(first, member);
        PointsResult result = engine.processPayment(second, member);

        assertEquals(0, result.getPointsAwarded());
        assertEquals(ProcessingOutcome.CAPPED, result.getOutcome());
        assertFalse(result.isSkippedAsDuplicate());
        assertEquals(100_000, member.getPointsForMonth(YearMonth.of(2026, 3)));
    }

    @Test
    void doesNotDoubleAwardPointsWhenSameWebhookEventIsResent() {
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent event = new PaymentEvent("evt-1", "member-1",
                new BigDecimal("1500"), false, LocalDate.of(2026, 3, 1));

        PointsResult first = engine.processPayment(event, member);
        PointsResult retry = engine.processPayment(event, member);

        assertEquals(1500, first.getPointsAwarded());
        assertEquals(ProcessingOutcome.AWARDED, first.getOutcome());
        assertEquals(0, retry.getPointsAwarded());
        assertEquals(ProcessingOutcome.DUPLICATE, retry.getOutcome());
        assertTrue(retry.isSkippedAsDuplicate());
    }

    @Test
    void doesNotDoubleAwardPointsWhenAnOlderEventIsResentOutOfOrder() {
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent eventA = new PaymentEvent("evt-A", "member-1",
                new BigDecimal("1000"), false, LocalDate.of(2026, 3, 1));
        PaymentEvent eventB = new PaymentEvent("evt-B", "member-1",
                new BigDecimal("2000"), false, LocalDate.of(2026, 3, 5));

        // Processing order as it can realistically happen with webhook retries:
        // A arrives, then B arrives, then A is redelivered by the processor.
        engine.processPayment(eventA, member);
        engine.processPayment(eventB, member);
        PointsResult resentA = engine.processPayment(eventA, member);

        assertEquals(0, resentA.getPointsAwarded());
        assertEquals(ProcessingOutcome.DUPLICATE, resentA.getOutcome());
        assertTrue(resentA.isSkippedAsDuplicate());
        assertEquals(3000, member.getPointsForMonth(YearMonth.of(2026, 3)));
    }

    @RepeatedTest(8)
    void awardsPointsAtMostOnceWhenTheSameEventArrivesConcurrently() throws Exception {
        int workerCount = 16;
        RewardsEngine concurrentEngine = new RewardsEngine(
                new PointsCalculator(), new ProcessedEventStore());
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent event = new PaymentEvent(
                "evt-concurrent",
                "member-1",
                new BigDecimal("1500"),
                false,
                LocalDate.of(2026, 3, 1));
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<PointsResult>> futures = new ArrayList<>();
            for (int worker = 0; worker < workerCount; worker++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return concurrentEngine.processPayment(event, member);
                }));
            }

            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();

            long awardedResults = 0;
            for (Future<PointsResult> future : futures) {
                if (future.get(2, TimeUnit.SECONDS).getOutcome() == ProcessingOutcome.AWARDED) {
                    awardedResults++;
                }
            }

            assertEquals(1, awardedResults,
                    "at most one worker may award points for the same eventId");
            assertEquals(1500, member.getPointsForMonth(YearMonth.of(2026, 3)));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void awardsEachEventExactlyOnceAcrossInterleavedRedeliveries() {
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent a = payment("evt-A", "1000");
        PaymentEvent b = payment("evt-B", "2000");
        PaymentEvent c = payment("evt-C", "3000");

        assertEquals(ProcessingOutcome.AWARDED, engine.processPayment(b, member).getOutcome());
        assertEquals(ProcessingOutcome.AWARDED, engine.processPayment(a, member).getOutcome());
        assertEquals(ProcessingOutcome.DUPLICATE, engine.processPayment(b, member).getOutcome());
        assertEquals(ProcessingOutcome.AWARDED, engine.processPayment(c, member).getOutcome());
        assertEquals(ProcessingOutcome.DUPLICATE, engine.processPayment(a, member).getOutcome());
        assertEquals(ProcessingOutcome.DUPLICATE, engine.processPayment(c, member).getOutcome());
        assertEquals(ProcessingOutcome.DUPLICATE, engine.processPayment(b, member).getOutcome());
        assertEquals(6000, member.getPointsForMonth(MARCH));
    }

    @RepeatedTest(8)
    void awardsEachEventOnceWhenManyEventsAreRedeliveredConcurrently() throws Exception {
        MemberAccount member = new MemberAccount("member-1", 0);
        int eventCount = 20;
        int deliveriesPerEvent = 4;
        List<PaymentEvent> deliveries = new ArrayList<>();
        for (int i = 0; i < eventCount; i++) {
            PaymentEvent event = payment("evt-" + i, "100");
            for (int d = 0; d < deliveriesPerEvent; d++) {
                deliveries.add(event);
            }
        }

        Map<String, Integer> awardsByEvent = new ConcurrentHashMap<>();
        runConcurrently(deliveries.stream().map(event -> (Callable<PointsResult>) () -> {
            PointsResult result = engine.processPayment(event, member);
            if (result.getOutcome() == ProcessingOutcome.AWARDED) {
                awardsByEvent.merge(event.getEventId(), 1, Integer::sum);
            }
            return result;
        }).toList());

        assertEquals(eventCount, awardsByEvent.size(), "every distinct event is awarded");
        awardsByEvent.forEach((eventId, awards) ->
                assertEquals(1, awards, eventId + " must be awarded exactly once"));
        assertEquals(eventCount * 100L, member.getPointsForMonth(MARCH));
    }

    @RepeatedTest(8)
    void neverExceedsMonthlyCapWhenDistinctEventsArriveConcurrently() throws Exception {
        MemberAccount member = new MemberAccount("member-1", 0);
        List<Callable<PointsResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            PaymentEvent event = payment("evt-" + i, "30000");
            tasks.add(() -> engine.processPayment(event, member));
        }

        List<PointsResult> results = runConcurrently(tasks);

        long reportedPoints = results.stream().mapToLong(PointsResult::getPointsAwarded).sum();
        assertEquals(100_000, reportedPoints, "points reported to callers");
        assertEquals(100_000, member.getPointsForMonth(MARCH), "points recorded on the account");
    }

    @Test
    void retryIsProcessedWhenAnEarlierAttemptFailedBeforeAwardingPoints() {
        AtomicBoolean failNextCall = new AtomicBoolean(true);
        PointsCalculator flakyCalculator = new PointsCalculator() {
            @Override
            public long calculateBasePoints(PaymentEvent event) {
                if (failNextCall.getAndSet(false)) {
                    throw new IllegalStateException("transient failure");
                }
                return super.calculateBasePoints(event);
            }
        };
        RewardsEngine flakyEngine = new RewardsEngine(flakyCalculator, new ProcessedEventStore());
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent event = payment("evt-1", "1500");

        assertThrows(IllegalStateException.class, () -> flakyEngine.processPayment(event, member));
        PointsResult retry = flakyEngine.processPayment(event, member);

        assertEquals(ProcessingOutcome.AWARDED, retry.getOutcome());
        assertEquals(1500, member.getPointsForMonth(MARCH));
    }

    @Test
    void doesNotReportCappedWhenAPaymentEarnsNoPointsBelowTheCap() {
        MemberAccount member = new MemberAccount("member-1", 0);

        PointsResult result = engine.processPayment(payment("evt-cents", "0.50"), member);

        assertEquals(0, result.getPointsAwarded());
        assertEquals(ProcessingOutcome.AWARDED, result.getOutcome());
    }

    @Test
    void awardsOnlyTheRemainingPointsWhenAPaymentCrossesTheCap() {
        MemberAccount member = new MemberAccount("member-1", 0);
        engine.processPayment(payment("evt-1", "99000"), member);

        PointsResult result = engine.processPayment(payment("evt-2", "1500"), member);

        assertEquals(1000, result.getPointsAwarded());
        assertEquals(ProcessingOutcome.AWARDED, result.getOutcome());
        assertEquals(100_000, member.getPointsForMonth(MARCH));
    }

    @Test
    void monthlyCapResetsInTheNextMonth() {
        MemberAccount member = new MemberAccount("member-1", 0);
        engine.processPayment(payment("evt-march", "150000"), member);

        PointsResult april = engine.processPayment(new PaymentEvent("evt-april", "member-1",
                new BigDecimal("1500"), false, LocalDate.of(2026, 4, 1)), member);

        assertEquals(1500, april.getPointsAwarded());
        assertEquals(ProcessingOutcome.AWARDED, april.getOutcome());
    }

    @Test
    void reportsDuplicateNotCappedWhenACappedEventIsRedelivered() {
        MemberAccount member = new MemberAccount("member-1", 0);
        engine.processPayment(payment("evt-1", "150000"), member);
        PaymentEvent capped = payment("evt-2", "50");
        engine.processPayment(capped, member);

        PointsResult retry = engine.processPayment(capped, member);

        assertEquals(ProcessingOutcome.DUPLICATE, retry.getOutcome());
        assertEquals(100_000, member.getPointsForMonth(MARCH));
    }

    @Test
    void roundsFractionalDollarsDown() {
        MemberAccount member = new MemberAccount("member-1", 0);

        PointsResult result = engine.processPayment(payment("evt-1", "1500.99"), member);

        assertEquals(1500, result.getPointsAwarded());
    }

    @Test
    void doesNotApplyStreakBonusBelowSixMonths() {
        MemberAccount member = new MemberAccount("member-1", 5);

        PointsResult result = engine.processPayment(payment("evt-1", "2000"), member);

        assertEquals(2000, result.getPointsAwarded());
    }

    private static PaymentEvent payment(String eventId, String amount) {
        return new PaymentEvent(eventId, "member-1", new BigDecimal(amount), false, MARCH_1);
    }

    /** Starts all the tasks at the same moment so they really overlap. */
    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
