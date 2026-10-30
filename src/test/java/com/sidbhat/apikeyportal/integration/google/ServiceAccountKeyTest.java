package com.sidbhat.apikeyportal.integration.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ServiceAccountKeyTest {

    @Test
    void parsesTheFieldsThePortalNeeds() {
        ServiceAccountKey key = ServiceAccountKey.parse("""
                {
                  "type": "service_account",
                  "project_id": "my-project",
                  "private_key_id": "abc",
                  "private_key": "-----BEGIN PRIVATE KEY-----\\nxxx\\n-----END PRIVATE KEY-----\\n",
                  "client_email": "portal@my-project.iam.gserviceaccount.com",
                  "token_uri": "https://oauth2.googleapis.com/token"
                }
                """);

        assertThat(key.projectId()).isEqualTo("my-project");
        assertThat(key.clientEmail()).isEqualTo("portal@my-project.iam.gserviceaccount.com");
        assertThat(key.privateKey()).startsWith("-----BEGIN PRIVATE KEY-----");
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> ServiceAccountKey.parse("{not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void rejectsNonServiceAccountJson() {
        assertThatThrownBy(() -> ServiceAccountKey.parse("{\"type\":\"authorized_user\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service account key");
    }

    @Test
    void rejectsKeyFileWithMissingFields() {
        assertThatThrownBy(() -> ServiceAccountKey.parse(
                "{\"type\":\"service_account\",\"project_id\":\"my-project\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void neverIncludesThePrivateKeyInToString() {
        ServiceAccountKey key = new ServiceAccountKey("p", "e@p.iam", "SECRET-KEY-MATERIAL");
        assertThat(key.toString()).doesNotContain("SECRET-KEY-MATERIAL");
    }
}
