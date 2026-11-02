package com.sidbhat.apikeyportal.integration.aws;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Endpoints for AWS. Overridable so tests can point at a stub. Both are the
 * global endpoints, which sign as region {@code us-east-1}.
 */
@ConfigurationProperties(prefix = "portal.aws")
public record AwsProperties(
        @DefaultValue("https://iam.amazonaws.com") String iamBaseUrl,
        @DefaultValue("https://sts.amazonaws.com") String stsBaseUrl) {
}
