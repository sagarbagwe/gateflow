package com.gateflow.http;

import com.fasterxml.jackson.core.JsonParser;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonInputConfiguration {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer rejectDuplicateJsonKeys() {
        return builder -> builder.featuresToEnable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
}
