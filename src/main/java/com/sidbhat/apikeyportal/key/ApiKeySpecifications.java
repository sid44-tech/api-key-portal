package com.sidbhat.apikeyportal.key;

import java.util.Locale;

import javax.persistence.criteria.Order;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

final class ApiKeySpecifications {

    private ApiKeySpecifications() {
    }

    /** Case-insensitive match on key name or service name. No-op for blank text. */
    static Specification<ApiKey> textContains(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String pattern = "%" + text.trim().toLowerCase(Locale.ROOT) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern),
                cb.like(cb.lower(root.get("serviceName")), pattern));
    }

    /** Exact match on service name. No-op for blank service. */
    static Specification<ApiKey> serviceIs(String serviceName) {
        if (!StringUtils.hasText(serviceName)) {
            return null;
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
                case SERVICE:
                default:
                    query.orderBy(byService, byName);
            }
            return null;
        };
    }
}
