package id.saryu.saryuguard.mfa;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Immutable-except-for-mutable-counters per-player MFA session record.
 *
 * <p>Thread-safe: state volatile, attempts AtomicInteger, pendingSince volatile.</p>
 */
public final class MfaSession {

    private final UUID uuid;
    private volatile MfaState state;
    private volatile long pendingSince; // System.currentTimeMillis() when PENDING_MFA was set
    private final AtomicInteger failedAttempts = new AtomicInteger(0);
    private volatile long nextAttemptAt;  // epoch ms; 0 = no delay

    public MfaSession(UUID uuid, MfaState initialState) {
        this.uuid = uuid;
        this.state = initialState;
        this.pendingSince = System.currentTimeMillis();
    }

    // -------------------------------------------------------------------------
    // State management
    // -------------------------------------------------------------------------

    public MfaState getState() { return state; }

    public void setState(MfaState newState) {
        this.state = newState;
        if (newState == MfaState.PENDING_MFA) {
            this.pendingSince = System.currentTimeMillis();
        }
    }

    // -------------------------------------------------------------------------
    // Timing
    // -------------------------------------------------------------------------

    /** Returns millis since PENDING_MFA was last set. */
    public long millisSincePending() {
        return System.currentTimeMillis() - pendingSince;
    }

    public long getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(long epochMs) { this.nextAttemptAt = epochMs; }

    /** @return true if the current time is before the next-attempt window. */
    public boolean isRateLimited() {
        return nextAttemptAt > 0 && System.currentTimeMillis() < nextAttemptAt;
    }

    // -------------------------------------------------------------------------
    // Attempt tracking
    // -------------------------------------------------------------------------

    public int getFailedAttempts() { return failedAttempts.get(); }

    /** Increments and returns the new attempt count. */
    public int incrementFailedAttempts() { return failedAttempts.incrementAndGet(); }

    public void resetFailedAttempts() { failedAttempts.set(0); }

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    public UUID getUuid() { return uuid; }

    @Override
    public String toString() {
        return "MfaSession{uuid=" + uuid + ", state=" + state
                + ", attempts=" + failedAttempts.get() + "}";
    }
}
