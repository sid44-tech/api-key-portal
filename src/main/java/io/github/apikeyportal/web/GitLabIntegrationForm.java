package io.github.apikeyportal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The values the GitLab setup wizard collects: where GitLab is (it is routinely
 * self-hosted), which project, and the access token the portal will act with.
 */
public class GitLabIntegrationForm {

    @NotBlank(message = "Give the integration a name")
    @Size(max = 100)
    private String name;

    @NotBlank(message = "Enter the GitLab URL")
    @Pattern(regexp = "https://.*", message = "The GitLab URL must start with https://")
    @Size(max = 100)
    private String baseUrl = "https://gitlab.com";

    /** Numeric ID or full path, e.g. {@code 42} or {@code group/app}. */
    @NotBlank(message = "Enter the project ID or path")
    @Size(max = 100)
    private String project;

    @NotBlank(message = "Enter the access token")
    @Size(max = 256)
    private String token;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null ? null : baseUrl.trim();
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project == null ? null : project.trim();
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token == null ? null : token.trim();
    }

    @Override
    public String toString() {
        // never include the token here, this ends up in logs
        return "GitLabIntegrationForm{name='" + name + "', baseUrl='" + baseUrl + "', project='" + project + "'}";
    }
}
