package com.dbonilla.ticketing.api.config;

import com.dbonilla.ticketing.api.model.order.OrderPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import java.time.Clock;

@Configuration
@ComponentScan(basePackages = "com.dbonilla.ticketing.api.usecase",
        includeFilters = {
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = "^.+UseCase$")
        },
        useDefaultFilters = false)
public class UseCasesConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public OrderPolicy orderPolicy(BusinessProperties properties) {
        return new OrderPolicy(properties.reconciliation().deadline());
    }
}
