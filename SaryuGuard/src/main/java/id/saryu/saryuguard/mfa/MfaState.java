package id.saryu.saryuguard.mfa;

/**
 * Authentication state machine for staff 2FA.
 *
 * <p>State transitions:
 * <pre>
 * UNAUTHENTICATED ──(join / trigger)──► PENDING_MFA
 * PENDING_MFA ──(correct code)──────► AUTHENTICATED
 * PENDING_MFA ──(timeout / kick)────► UNAUTHENTICATED  (on rejoin → PENDING_MFA)
 * PENDING_MFA ──(too many fails)────► LOCKED
 * LOCKED ──(console reset)──────────► UNAUTHENTICATED
 * </pre>
 */
public enum MfaState {

    /** Player has joined but 2FA not yet triggered (or session expired). */
    UNAUTHENTICATED,

    /**
     * 2FA required; player is awaiting TOTP code input.
     * Movement, commands, chat, and inventory are restricted.
     */
    PENDING_MFA,

    /** Player has successfully authenticated. Full access granted. */
    AUTHENTICATED,

    /**
     * Too many failed attempts. Player is kicked and blocked until an admin
     * resets via console: {@code sg 2fa reset <player>}.
     */
    LOCKED
}
