package com.yesmadam.schedulerservice.jobs.billingcheck.service;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.Getter;
import lombok.Setter;

/**
 * Per-environment knobs for the billing-check job ({@code billingcheck.*}).
 *
 * <p>Validated at startup (CLAUDE.md rule 21): a blank template or an empty category list would
 * otherwise only surface when the first run tried to send, and a category list that silently
 * matched nothing looks identical to "no bookings today".
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "billingcheck")
public class BillingCheckProperties {

  /** Approved WhatsApp template name (Utility, English, Yes/No quick-reply buttons). */
  private String templateName;

  /** {@code tbl_order.category_id} values in scope (PRD: Beauty and Beauty Salon at Home). */
  private List<Integer> categoryIds = new ArrayList<>();

  /** Orders handled per activity call — one retryable chunk, one cursor step. */
  private int chunkSize = 100;

  @PostConstruct
  void validate() {
    if (!StringUtils.hasText(templateName)) {
      throw new IllegalStateException("billingcheck.template-name must be set");
    }
    if (categoryIds == null || categoryIds.isEmpty()) {
      throw new IllegalStateException("billingcheck.category-ids must list at least one category id");
    }
    if (chunkSize < 1 || chunkSize > 500) {
      throw new IllegalStateException("billingcheck.chunk-size must be 1-500, was " + chunkSize);
    }
  }
}
