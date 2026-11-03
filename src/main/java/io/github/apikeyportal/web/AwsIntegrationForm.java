package io.github.apikeyportal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The values the AWS setup wizard collects: which account, and the access key
 * pair of the IAM user the wizard has the admin create for the portal.
 */
public class AwsIntegrationForm {

    @NotBlank(message = "Give the integration a name")
    @Size(max = 100)
    private String name;

    /** The twelve-digit account id, shown under the account menu in the console. */
    @NotBlank(message = "Enter the AWS account ID")
    @Pattern(regexp = "\\d{12}", message = "An AWS account ID is exactly twelve digits")
    private String accountId;

    @NotBlank(message = "Enter the access key ID")
    @Size(max = 128)
    private String accessKeyId;

    @NotBlank(message = "Enter the secret access key")
    @Size(max = 256)
    private String secretAccessKey;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId == null ? null : accountId.trim();
    }

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId == null ? null : accessKeyId.trim();
    }

    public String getSecretAccessKey() {
        return secretAccessKey;
    }

    public void setSecretAccessKey(String secretAccessKey) {
        this.secretAccessKey = secretAccessKey == null ? null : secretAccessKey.trim();
    }

    @Override
    public String toString() {
        // never include the secret access key here, this ends up in logs
        return "AwsIntegrationForm{name='" + name + "', accountId='" + accountId + "'}";
    }
}
