import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Circuit Breaker pattern implementation.
 *
 * States:
 *   CLOSED    - Normal operation. Requests pass through. Failures are counted.
 *   OPEN      - Failing fast. Requests are rejected immediately without calling the service.
 *   HALF_OPEN - Recovery probe. A limited number of requests are allowed through to test
 *               if the downstream service has recovered.
 */
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;       // failures in window before opening
    private final long windowMillis;          // sliding window duration
    private final long recoveryTimeoutMillis; // how long to stay OPEN before trying HALF_OPEN
    private final int halfOpenMaxAttempts;    // max probe requests allowed in HALF_OPEN

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenAttempts = new AtomicInteger(0);
    private final AtomicLong windowStartTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong openedAt = new AtomicLong(0);

    public CircuitBreaker(int failureThreshold, long windowMillis,
                          long recoveryTimeoutMillis, int halfOpenMaxAttempts) {
        this.failureThreshold = failureThreshold;
        this.windowMillis = windowMillis;
        this.recoveryTimeoutMillis = recoveryTimeoutMillis;
        this.halfOpenMaxAttempts = halfOpenMaxAttempts;
    }

    /**
     * Execute the given operation through the circuit breaker.
     * Throws CircuitOpenException if the circuit is OPEN.
     */
    public <T> T execute(Supplier<T> operation) {
        State current = getEffectiveState();

        if (current == State.OPEN) {
            throw new CircuitOpenException("Circuit is OPEN — request rejected fast.");
        }

        if (current == State.HALF_OPEN) {
            int attempts = halfOpenAttempts.incrementAndGet();
            if (attempts > halfOpenMaxAttempts) {
                throw new CircuitOpenException("Circuit is HALF_OPEN — probe limit reached.");
            }
        }

        try {
            T result = operation.get();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure();
            throw e;
        }
    }

    /**
     * Checks if the circuit should transition from OPEN to HALF_OPEN
     * based on the recovery timeout.
     */
    private State getEffectiveState() {
        if (state.get() == State.OPEN) {
            long elapsed = System.currentTimeMillis() - openedAt.get();
            if (elapsed >= recoveryTimeoutMillis) {
                // Attempt transition to HALF_OPEN
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    halfOpenAttempts.set(0);
                    System.out.println("[CircuitBreaker] Transitioning OPEN -> HALF_OPEN");
                }
            }
        }
        return state.get();
    }

    private void onSuccess() {
        State current = state.get();
        if (current == State.HALF_OPEN) {
            // Probe succeeded — recover to CLOSED
            if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                failureCount.set(0);
                windowStartTime.set(System.currentTimeMillis());
                System.out.println("[CircuitBreaker] Transitioning HALF_OPEN -> CLOSED (recovered)");
            }
        } else if (current == State.CLOSED) {
            // Reset failure count if window has expired
            resetWindowIfExpired();
        }
    }

    private void onFailure() {
        State current = state.get();
        if (current == State.HALF_OPEN) {
            // Probe failed — fault is still present, revert to OPEN
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                openedAt.set(System.currentTimeMillis());
                System.out.println("[CircuitBreaker] Transitioning HALF_OPEN -> OPEN (probe failed)");
            }
            return;
        }

        resetWindowIfExpired();

        int failures = failureCount.incrementAndGet();
        System.out.println("[CircuitBreaker] Failure recorded: " + failures + "/" + failureThreshold);

        if (failures >= failureThreshold) {
            if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                openedAt.set(System.currentTimeMillis());
                System.out.println("[CircuitBreaker] Transitioning CLOSED -> OPEN (threshold reached)");
            }
        }
    }

    private void resetWindowIfExpired() {
        long now = System.currentTimeMillis();
        long windowStart = windowStartTime.get();
        if (now - windowStart >= windowMillis) {
            if (windowStartTime.compareAndSet(windowStart, now)) {
                failureCount.set(0);
            }
        }
    }

    public State getState() {
        return state.get();
    }

    public static class CircuitOpenException extends RuntimeException {
        public CircuitOpenException(String message) {
            super(message);
        }
    }

    // --- Demo ---
    public static void main(String[] args) throws InterruptedException {
        // Open circuit after 3 failures in a 10s window; recover after 2s; allow 1 probe in HALF_OPEN
        CircuitBreaker cb = new CircuitBreaker(3, 10_000, 2_000, 1);

        Supplier<String> faultyService = () -> {
            throw new RuntimeException("Service unavailable");
        };
        Supplier<String> healthyService = () -> "OK";

        System.out.println("-- Triggering failures to open circuit --");
        for (int i = 0; i < 4; i++) {
            try {
                cb.execute(faultyService);
            } catch (Exception e) {
                System.out.println("Caught: " + e.getMessage());
            }
        }

        System.out.println("\nState: " + cb.getState()); // OPEN

        System.out.println("\n-- Waiting for recovery timeout (2s) --");
        Thread.sleep(2500);

        System.out.println("\n-- Sending probe request (HALF_OPEN) --");
        try {
            String result = cb.execute(healthyService);
            System.out.println("Probe result: " + result);
        } catch (Exception e) {
            System.out.println("Caught: " + e.getMessage());
        }

        System.out.println("\nFinal state: " + cb.getState()); // CLOSED
    }
}