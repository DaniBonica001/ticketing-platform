package com.dbonilla.ticketing.processor.config;

import co.com.bancolombia.secretsmanager.api.GenericManagerAsync;
import co.com.bancolombia.secretsmanager.config.AWSSecretsManagerConfig;
import co.com.bancolombia.secretsmanager.connector.AWSSecretManagerConnectorAsync;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;

@Configuration
public class SecretsConfig {

    private static final int CACHE_SIZE = 5;
    private static final int CACHE_SECONDS = 3600;

    @Bean
    public GenericManagerAsync getSecretManager(@Value("${aws.region}") String region,
                                                @Value("${aws.endpoint:}") String endpoint) {
        return new AWSSecretManagerConnectorAsync(getConfig(region, endpoint));
    }

    private AWSSecretsManagerConfig getConfig(String region, String endpoint) {
        AWSSecretsManagerConfig.AWSSecretsManagerConfigBuilder builder = AWSSecretsManagerConfig.builder()
                .region(Region.of(region))
                .cacheSize(CACHE_SIZE)
                .cacheSeconds(CACHE_SECONDS);
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpoint(endpoint);
        }
        return builder.build();
    }
}
