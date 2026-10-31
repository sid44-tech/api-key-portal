package com.sidbhat.apikeyportal.web;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.sidbhat.apikeyportal.key.ApiKeyService;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccountService;

/**
 * Returns the decrypted value of a single key. Values are only ever sent to the
 * browser on demand through this endpoint, never rendered into pages, and only
 * to the person who owns the key - group members see the metadata and nothing more.
 */
@RestController
public class ApiKeyValueController {

    private final ApiKeyService apiKeyService;
    private final UserAccountService userService;

    public ApiKeyValueController(ApiKeyService apiKeyService, UserAccountService userService) {
        this.apiKeyService = apiKeyService;
        this.userService = userService;
    }

    @GetMapping("/keys/{id}/value")
    public ResponseEntity<Map<String, String>> value(@PathVariable Long id,
            @AuthenticationPrincipal PortalUser principal) {
        String value = apiKeyService.revealValue(id, userService.getCurrent(principal));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("value", value));
    }
}
