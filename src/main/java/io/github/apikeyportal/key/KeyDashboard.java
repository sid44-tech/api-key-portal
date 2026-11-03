package io.github.apikeyportal.key;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * What a group's keys need attention for, worked out from the keys themselves
 * so that it can never disagree with the badges on the list: the same
 * {@link ExpirationStatus} decides "expiring" here and there.
 *
 * @param total           keys in scope
 * @param expired         how many have already expired (hidden from the list by default)
 * @param expiringSoon    expiring within {@link ExpirationStatus#EXPIRING_SOON_DAYS}, soonest first
 * @param failedRotations keys whose last automatic replacement or deletion failed, latest first
 * @param neverRevealed   live keys nobody has revealed since they were added at least
 *                        {@link #UNUSED_AFTER_DAYS} ago - possibly unused, oldest first
 */
public record KeyDashboard(long total, long expired, List<ApiKey> expiringSoon, List<ApiKey> failedRotations,
        List<ApiKey> neverRevealed) {

    /**
     * How long a key goes unrevealed before that is worth a mention. A key added
     * yesterday has simply not been needed yet; one from a month ago that nobody
     * has ever looked at may not be needed at all.
     */
    public static final int UNUSED_AFTER_DAYS = 30;

    static KeyDashboard of(Collection<ApiKey> keys, LocalDate today, Instant now) {
        Instant unusedBefore = now.minus(UNUSED_AFTER_DAYS, ChronoUnit.DAYS);
        Comparator<ApiKey> byServiceAndName = Comparator.comparing(ApiKey::getServiceName)
                .thenComparing(ApiKey::getName);

        long expired = keys.stream().filter(k -> ExpirationStatus.of(k.getExpiresOn(), today) == ExpirationStatus.EXPIRED)
                .count();
        List<ApiKey> expiringSoon = keys.stream()
                .filter(k -> ExpirationStatus.of(k.getExpiresOn(), today) == ExpirationStatus.EXPIRING_SOON)
                .sorted(Comparator.comparing(ApiKey::getExpiresOn).thenComparing(byServiceAndName))
                .toList();
        List<ApiKey> failedRotations = keys.stream()
                .filter(k -> k.getRotationFailedAt() != null)
                .sorted(Comparator.comparing(ApiKey::getRotationFailedAt).reversed())
                .toList();
        List<ApiKey> neverRevealed = keys.stream()
                // live keys only: an expired or deleted one is already on a list of its own
                .filter(k -> k.getLastRevealedAt() == null && !k.isRevoked()
                        && ExpirationStatus.of(k.getExpiresOn(), today) != ExpirationStatus.EXPIRED
                        && k.getAddedAt() != null && !k.getAddedAt().isAfter(unusedBefore))
                .sorted(Comparator.comparing(ApiKey::getAddedAt).thenComparing(byServiceAndName))
                .toList();
        return new KeyDashboard(keys.size(), expired, expiringSoon, failedRotations, neverRevealed);
    }

    /** True when there is nothing to show but the counts. */
    public boolean isAllClear() {
        return expiringSoon.isEmpty() && failedRotations.isEmpty() && neverRevealed.isEmpty();
    }
}
