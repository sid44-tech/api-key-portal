package com.sidbhat.apikeyportal.key;

import java.util.Locale;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import com.sidbhat.apikeyportal.user.UserAccount;

final class ApiKeySpecifications {

    private ApiKeySpecifications() {
    }

    /**
     * The keys {@code viewer} may see listed: everything in their group, or the whole
     * portal for a super admin. This is metadata only - revealing a value is checked
     * separately and is always owner-only.
     */
    static Specification<ApiKey> visibleTo(UserAccount viewer) {
        if (viewer.isSuperAdmin()) {
            return Specification.unrestricted();
        }
        Long groupId = viewer.getGroupId();
        if (groupId == null) {
            // not in a group yet: only their own keys
            return (root, query, cb) -> cb.equal(root.get("owner").get("id"), viewer.getId());
        }
        return (root, query, cb) -> cb.equal(root.get("group").get("id"), groupId);
    }

    /** Case-insensitive match on key name, service name or owner. No-op for blank text. */
    static Specification<ApiKey> textContains(String text) {
        if (!StringUtils.hasText(text)) {
            return Specification.unrestricted();
        }
        String pattern = "%" + text.trim().toLowerCase(Locale.ROOT) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern),
                cb.like(cb.lower(root.get("serviceName")), pattern),
                cb.like(cb.lower(root.join("owner", JoinType.LEFT).get("displayName")), pattern));
    }

    /** Narrows to one person's keys, e.g. "only mine". No-op for a null owner. */
    static Specification<ApiKey> ownedBy(Long ownerId) {
        if (ownerId == null) {
            return Specification.unrestricted();
        }
        return (root, query, cb) -> cb.equal(root.get("owner").get("id"), ownerId);
    }

    /** Exact match on service name. No-op for blank service. */
    static Specification<ApiKey> serviceIs(String serviceName) {
        if (!StringUtils.hasText(serviceName)) {
            return Specification.unrestricted();
        }
        return (root, query, cb) -> cb.equal(root.get("serviceName"), serviceName);
    }

    /**
     * Orders by the given sort option. This lives in a Specification rather than a {@code Sort}
     * because expiry needs keys that never expire to go last, and H2 and Postgres disagree
     * on where nulls go by default.
     */
    static Specification<ApiKey> orderBy(ApiKeySort sort) {
        return (root, query, cb) -> {
            if (Long.class.equals(query.getResultType()) || long.class.equals(query.getResultType())) {
                // count query, ordering not allowed/needed
                return null;
            }
            Order byService = cb.asc(root.get("serviceName"));
            Order byName = cb.asc(root.get("name"));
            switch (sort) {
                case EXPIRES:
                    query.orderBy(
                            cb.asc(cb.selectCase().when(cb.isNull(root.get("expiresOn")), 1).otherwise(0)),
                            cb.asc(root.get("expiresOn")), byService, byName);
                    break;
                case CREATED:
                    query.orderBy(cb.desc(root.get("createdOn")), byService, byName);
                    break;
                case OWNER:
                    query.orderBy(cb.asc(root.join("owner", JoinType.LEFT).get("displayName")), byService, byName);
                    break;
                case SERVICE:
                default:
                    query.orderBy(byService, byName);
            }
            return null;
        };
    }
}
