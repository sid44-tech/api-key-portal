package com.sidbhat.apikeyportal.key;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public ApiKey getById(Long id) {
        return repository.findById(id).orElseThrow(() -> new ApiKeyNotFoundException(id));
    }

    @Transactional
    public ApiKey create(String name, String serviceName, String keyValue, LocalDate createdOn,
            LocalDate expiresOn) {
        ApiKey key = repository.save(new ApiKey(name.trim(), serviceName.trim(), keyValue.trim(),
                createdOn != null ? createdOn : LocalDate.now(), expiresOn));
        log.info("Created {}", key);
        return key;
    }

    @Transactional
    public ApiKey update(Long id, String name, String serviceName, String keyValue, LocalDate createdOn,
            LocalDate expiresOn) {
        ApiKey key = getById(id);
        key.setName(name.trim());
        key.setServiceName(serviceName.trim());
        if (keyValue != null && !keyValue.isBlank()) {
            key.setKeyValue(keyValue.trim());
        }
        key.setCreatedOn(createdOn);
        key.setExpiresOn(expiresOn);
        log.info("Updated {}", key);
        return key;
    }

    @Transactional
    public void delete(Long id) {
        ApiKey key = getById(id);
        repository.delete(key);
        log.info("Deleted {}", key);
    }
}
