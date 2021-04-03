package com.sidbhat.apikeyportal.key;

import java.util.Locale;

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
}
