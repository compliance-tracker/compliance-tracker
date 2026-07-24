package com.chrainx.compliance_tracker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

// @Configuration: a class whose job is to produce @Bean methods - Spring calls each one once
// at startup and keeps the returned object around to inject wherever it's asked for, same
// lifecycle as an @Component, just written as a factory method instead of a whole class.
@Configuration
public class SqsConfig {

    @Value("${aws.region}")
    private String region;

    // ":" default syntax: if aws.sqs.endpoint isn't set in application.properties, this is
    // just an empty string instead of throwing a "missing property" error at startup.
    @Value("${aws.sqs.endpoint:}")
    private String endpointOverride;

    @Bean
    public SqsClient sqsClient() {
        var builder = SqsClient.builder().region(Region.of(region));

        // Only present for local dev against LocalStack, which doesn't check credentials for
        // real - "test"/"test" are throwaway values, never real AWS keys. When endpointOverride
        // is blank (real AWS deployment), this block is skipped entirely and the SDK falls
        // back to its default credential chain (environment variables, ~/.aws/credentials,
        // or the deployed environment's IAM role).
        if (!endpointOverride.isBlank()) {
            builder.endpointOverride(URI.create(endpointOverride))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")));
        }

        return builder.build();
    }
}
