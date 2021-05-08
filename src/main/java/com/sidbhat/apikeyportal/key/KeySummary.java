package com.sidbhat.apikeyportal.key;

import java.util.Collection;

/**
 * Counts shown in the summary cards at the top of the key list.
 */
public final class KeySummary {

    private final long total;
    private final long expiringSoon;
    private final long expired;

    private KeySummary(long total, long expiringSoon, long expired) {
        this.total = total;
        this.expiringSoon = expiringSoon;
        this.expired = expired;
    }

    public static KeySummary of(Collection<ApiKey> keys) {
        long expiringSoon = 0;
        long expired = 0;
        for (ApiKey key : keys) {
            ExpirationStatus status = key.getExpirationStatus();
            if (status == ExpirationStatus.EXPIRING_SOON) {
                expiringSoon++;
            } else if (status == ExpirationStatus.EXPIRED) {
                expired++;
            }
        }
        return new KeySummary(keys.size(), expiringSoon, expired);
    }

    public long getTotal() {
        return total;
    }

    public long getExpiringSoon() {
        return expiringSoon;
    }

    public long getExpired() {
        return expired;
    }
}
