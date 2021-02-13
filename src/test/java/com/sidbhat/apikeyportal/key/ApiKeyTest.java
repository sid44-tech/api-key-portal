package com.sidbhat.apikeyportal.key;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class ApiKeyTest {

    @Test
    void maskedValueShowsLastFourCharactersOfLongValues() {
        ApiKey key = new ApiKey("CI", "GitHub", "ghp_0123456789abcd", LocalDate.now(), null);

        assertThat(key.getMaskedValue()).isEqualTo("••••••••abcd");
    }

    @Test
    void maskedValueHidesShortValuesEntirely() {
        ApiKey key = new ApiKey("CI", "GitHub", "short", LocalDate.now(), null);

        assertThat(key.getMaskedValue()).doesNotContain("short").doesNotContain("ort");
    }
}
