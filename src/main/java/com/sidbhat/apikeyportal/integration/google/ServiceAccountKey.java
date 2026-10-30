package com.sidbhat.apikeyportal.integration.google;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The fields the portal needs from a Google service account JSON key file.
 */
public record ServiceAccountKey(String projectId, String clientEmail, String privateKey) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Extracts the needed fields from a pasted JSON key file.
     *
     * @throws IllegalArgumentException with a user-displayable message if the JSON
     *         is malformed or not a service account key
     */
    public static ServiceAccountKey parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("is not valid JSON - paste the whole downloaded key file");
        }
        if (!"service_account".equals(text(root, "type"))) {
            throw new IllegalArgumentException(
                    "does not look like a service account key (its \"type\" is not \"service_account\")");
        }
        String projectId = text(root, "project_id");
        String clientEmail = text(root, "client_email");
        String privateKey = text(root, "private_key");
        if (projectId == null || clientEmail == null || privateKey == null) {
            throw new IllegalArgumentException(
                    "is missing one of project_id, client_email or private_key - paste the whole downloaded key file");
        }
        return new ServiceAccountKey(projectId, clientEmail, privateKey);
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    @Override
    public String toString() {
        // never include the private key here, this could end up in logs
        return "ServiceAccountKey{projectId='" + projectId + "', clientEmail='" + clientEmail + "'}";
    }
}
