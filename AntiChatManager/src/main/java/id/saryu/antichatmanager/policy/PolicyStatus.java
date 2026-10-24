package id.saryu.antichatmanager.policy;

/**
 * Policy status for AntiChatManager Lite.
 *
 * <p>Requirements:
 * - READY: valid cache loaded from database and active.
 * - DEGRADED: database query failed but last valid cache exists. Kept in memory,
 *   admin alerted (console + online admins, throttled).
 * - UNAVAILABLE: never had a valid policy (e.g. database down at startup, empty cache).
 *   Uses emergency-blacklist from config.yml (static, no database needed).
 *   Massive ERROR logged. /acm reload MUST report failure.</p>
 */
public enum PolicyStatus {
    READY,
    DEGRADED,
    UNAVAILABLE
}
