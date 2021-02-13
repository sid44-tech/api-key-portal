package com.sidbhat.apikeyportal.web;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.sidbhat.apikeyportal.key.ApiKeyService;

/**
 * Returns the decrypted value of a single key. Values are only ever sent to the
 * browser on demand through this endpoint, never rendered into pages.
 */
@RestController
public class ApiKeyValueController {

    private final ApiKeyService apiKeyService;

    public ApiKeyValueController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @GetMapping("/keys/{id}/value")
    public ResponseEntity<Map<String, String>> value(@PathVariable Long id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("value", apiKeyService.getById(id).getKeyValue()));
    }
}
