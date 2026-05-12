package me.apet97.breakcompliance.persistence.entities;

/**
 * Lifecycle states for a {@link RefreshSignal}. The webhook handler
 * records signals in {@link #PENDING}; the consumer drives them through
 * {@link #CLAIMED} → {@link #CONSUMED} or {@link #FAILED}, or marks them
 * {@link #COALESCED} when an in-flight run for the same workspace+date
 * range already covers them.
 *
 * <p>{@link #ACKNOWLEDGED} predates V10 — it was the original "user saw
 * this in the sidebar" terminal state. The consumer never writes it, but
 * pre-V10 rows can still carry it and the enum keeps the value so
 * {@code @Enumerated(EnumType.STRING)} can round-trip historical data.
 */
public enum RefreshSignalStatus {
    PENDING,
    ACKNOWLEDGED,
    CLAIMED,
    CONSUMED,
    FAILED,
    COALESCED
}
