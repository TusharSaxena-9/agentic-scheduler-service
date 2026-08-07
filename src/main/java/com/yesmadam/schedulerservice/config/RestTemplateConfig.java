package com.yesmadam.schedulerservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Provides the shared {@link RestTemplate} used for outbound HTTP calls (e.g. WhatsApp alerts).
 *
 * <p>Temporal clients, workers, and metrics are auto-configured by the Temporal Spring Boot starter
 * from the {@code spring.temporal.*} properties; workflow and activity implementations are
 * discovered via {@code @WorkflowImpl}/{@code @ActivityImpl} annotations.
 */
@Configuration
public class RestTemplateConfig {

  @Bean
  public RestTemplate restTemplate() {
    return new RestTemplate();
  }
}
