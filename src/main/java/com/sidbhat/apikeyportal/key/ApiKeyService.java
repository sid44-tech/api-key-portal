package com.sidbhat.apikeyportal.key;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public List<ApiKey> findAll() {
        return repository.findAllByOrderByServiceNameAscNameAsc();
    }

    public List<ApiKey> search(String text, String serviceName) {
        return search(text, serviceName, ApiKeySort.SERVICE);
    }

    public List<ApiKey> search(String text, String serviceName, ApiKeySort sort) {
        return repository.findAll(Specification.where(ApiKeySpecifications.textContains(text))
                .and(ApiKeySpecifications.serviceIs(serviceName))
                .and(ApiKeySpecifications.orderBy(sort != null ? sort : ApiKeySort.SERVICE)));
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

    @Transactional
    public ApiKey create(String name, String serviceName, String keyValue, LocalDate createdOn,
            LocalDate expiresOn, String notes) {
        ApiKey newKey = new ApiKey(name.trim(), serviceName.trim(), keyValue.trim(),
                createdOn != null ? createdOn : LocalDate.now(), expiresOn);
        newKey.setNotes(trimToNull(notes));
        ApiKey key = repository.save(newKey);
        log.info("Created {}", key);
        return key;
    }

    @Transactional
    public ApiKey update(Long id, String name, String serviceName, String keyValue, LocalDate createdOn,
            LocalDate expiresOn, String notes) {
        ApiKey key = getById(id);
        key.setName(name.trim());
        key.setServiceName(serviceName.trim());
        if (keyValue != null && !keyValue.isBlank()) {
            key.setKeyValue(keyValue.trim());
        }
        key.setCreatedOn(createdOn);
        key.setExpiresOn(expiresOn);
        key.setNotes(trimToNull(notes));
        log.info("Updated {}", key);
        return key;
    }

    @Transactional
    public void delete(Long id) {
        ApiKey key = getById(id);
        repository.delete(key);
        log.info("Deleted {}", key);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
