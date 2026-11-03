package com.paygate.risk;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RiskService {

    public enum Decision {
        APPROVE,
        REVIEW,
        REJECT
    }

    public record Evaluation(int score, Decision decision, List<String> triggeredRules) {
    }

    static final String RULE_AMOUNT_LIMIT = "AMOUNT_ABOVE_LIMIT";
    static final String RULE_VELOCITY = "VELOCITY_EXCEEDED";
    static final String RULE_CURRENCY_MISMATCH = "CURRENCY_MISMATCH";
    static final String RULE_DISPOSABLE_EMAIL = "DISPOSABLE_EMAIL_DOMAIN";

    private static final int SCORE_AMOUNT_LIMIT = 40;
    private static final int SCORE_VELOCITY = 35;
    private static final int SCORE_CURRENCY_MISMATCH = 20;
    private static final int SCORE_DISPOSABLE_EMAIL = 25;
    private static final int MAX_SCORE = 100;

    private static final long VELOCITY_WINDOW_MILLIS = 10 * 60 * 1000L;

    private static final Set<String> DISPOSABLE_DOMAINS = Set.of(
            "mailinator.com",
            "guerrillamail.com",
            "10minutemail.com",
            "tempmail.com",
            "trashmail.com",
            "yopmail.com",
            "throwawaymail.com",
            "sharklasers.com",
            "getnada.com",
            "dispostable.com");

    private final long maxAmount;
    private final int rejectThreshold;
    private final int reviewThreshold;
    private final int velocityLimit;

    private final ConcurrentHashMap<String, Deque<Long>> attemptsByFingerprint = new ConcurrentHashMap<>();

    public RiskService(@Value("${risk.max-amount:50000000}") long maxAmount,
                       @Value("${risk.reject-threshold:80}") int rejectThreshold,
                       @Value("${risk.review-threshold:50}") int reviewThreshold,
                       @Value("${risk.velocity-limit:5}") int velocityLimit) {
        this.maxAmount = maxAmount;
        this.rejectThreshold = rejectThreshold;
        this.reviewThreshold = reviewThreshold;
        this.velocityLimit = velocityLimit;
    }

    public Evaluation evaluate(long amount,
                               String currency,
                               String cardCurrency,
                               String cardFingerprint,
                               String customerEmail) {
        int score = 0;
        List<String> triggered = new ArrayList<>();

        if (amount > maxAmount) {
            score += SCORE_AMOUNT_LIMIT;
            triggered.add(RULE_AMOUNT_LIMIT);
        }

        if (exceedsVelocity(cardFingerprint)) {
            score += SCORE_VELOCITY;
            triggered.add(RULE_VELOCITY);
        }

        if (currencyMismatch(currency, cardCurrency)) {
            score += SCORE_CURRENCY_MISMATCH;
            triggered.add(RULE_CURRENCY_MISMATCH);
        }

        if (isDisposableEmail(customerEmail)) {
            score += SCORE_DISPOSABLE_EMAIL;
            triggered.add(RULE_DISPOSABLE_EMAIL);
        }

        int finalScore = Math.min(score, MAX_SCORE);
        return new Evaluation(finalScore, decide(finalScore), List.copyOf(triggered));
    }

    private Decision decide(int score) {
        if (score >= rejectThreshold) {
            return Decision.REJECT;
        }
        if (score >= reviewThreshold) {
            return Decision.REVIEW;
        }
        return Decision.APPROVE;
    }

    private boolean exceedsVelocity(String cardFingerprint) {
        if (cardFingerprint == null || cardFingerprint.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long cutoff = now - VELOCITY_WINDOW_MILLIS;
        Deque<Long> attempts = attemptsByFingerprint.computeIfAbsent(cardFingerprint,
                key -> new ConcurrentLinkedDeque<>());
        int count;
        synchronized (attempts) {
            while (!attempts.isEmpty() && attempts.peekFirst() < cutoff) {
                attempts.pollFirst();
            }
            attempts.addLast(now);
            count = attempts.size();
        }
        evictStale(cutoff);
        return count > velocityLimit;
    }

    private void evictStale(long cutoff) {
        if (attemptsByFingerprint.size() < 10_000) {
            return;
        }
        attemptsByFingerprint.entrySet().removeIf(entry -> {
            Deque<Long> deque = entry.getValue();
            synchronized (deque) {
                Long last = deque.peekLast();
                return last == null || last < cutoff;
            }
        });
    }

    private boolean currencyMismatch(String currency, String cardCurrency) {
        if (currency == null || currency.isBlank() || cardCurrency == null || cardCurrency.isBlank()) {
            return false;
        }
        return !currency.trim().equalsIgnoreCase(cardCurrency.trim());
    }

    private boolean isDisposableEmail(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return false;
        }
        String domain = email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
        return DISPOSABLE_DOMAINS.contains(domain);
    }
}
