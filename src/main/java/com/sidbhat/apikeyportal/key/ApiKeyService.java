package com.sidbhat.apikeyportal.key;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private final ApiKeyRepository repository;

    public ApiKeyService(ApiKeyRepository repository) {
        this.repository = repository;
    }

    public static final int PAGE_SIZE = 20;

    /**
     * One page of keys matching the filters.
     *
     * @param page zero-based page number
     */
    public Page<ApiKey> search(String text, String serviceName, ApiKeySort sort, int page) {
        // ordering is applied by the specification, so the PageRequest stays unsorted
        return repository.findAll(Specification.allOf(ApiKeySpecifications.textContains(text),
                        ApiKeySpecifications.serviceIs(serviceName),
                        ApiKeySpecifications.orderBy(sort != null ? sort : ApiKeySort.SERVICE)),
                PageRequest.of(Math.max(page, 0), PAGE_SIZE));
    }

    /**
     * Status counts across all keys. Computed in memory using {@link ExpirationStatus} so
     * there's a single definition of "expiring soon" - fine for the number of keys one
     * person has.
     */
    public KeySummary getSummary() {
        return KeySummary.of(repository.findAll());
    }

    public List<String> findServiceNames() {
        return repository.findDistinctServiceNames();
    }

    public ApiKey getById(Long id) {
        return repository.findById(id).orElseThrow(() -> new ApiKeyNotFoundException(id));
    }

    /**
     * Returns the decrypted value of a key and records that it was revealed.
     */
    @Transactional
    public String revealValue(Long id, String revealedBy) {
        ApiKey key = getById(id);
        repository.markRevealed(id, Instant.now());
        log.info("Value of {} revealed by {}", key, revealedBy);
        return key.getKeyValue();
    }

    @Transactional
    public ApiKey create(ApiKeyDetails details) {
        ApiKey newKey = new ApiKey(details.getName().trim(), details.getServiceName().trim(),
                details.getKeyValue().trim(),
                details.getCreatedOn() != null ? details.getCreatedOn() : LocalDate.now(),
                details.getExpiresOn());
        newKey.setNotes(trimToNull(details.getNotes()));
        ApiKey key = repository.save(newKey);
        log.info("Created {}", key);
        return key;
    }

    @Transactional
    public ApiKey update(Long id, ApiKeyDetails details) {
        ApiKey key = getById(id);
        key.setName(details.getName().trim());
        key.setServiceName(details.getServiceName().trim());
        // blank means "keep the current value"
        String keyValue = trimToNull(details.getKeyValue());
        if (keyValue != null) {
            key.setKeyValue(keyValue);
        }
        key.setCreatedOn(details.getCreatedOn());
        key.setExpiresOn(details.getExpiresOn());
        key.setNotes(trimToNull(details.getNotes()));
        log.info("Updated {}", key);
        return key;
    }

    @Transactional
    public ApiKey delete(Long id) {
        ApiKey key = getById(id);
        repository.delete(key);
        log.info("Deleted {}", key);
        return key;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
