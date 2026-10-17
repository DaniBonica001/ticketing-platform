package com.dbonilla.ticketing.api.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Bounds the bytes buffered per request body (DataBufferLimitException is answered with 413).
 */
@Configuration
public class WebFluxCodecConfig implements WebFluxConfigurer {

    @Override
    public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
        configurer.defaultCodecs().maxInMemorySize(BodySizeLimitFilter.MAX_BODY_BYTES);
    }
}
