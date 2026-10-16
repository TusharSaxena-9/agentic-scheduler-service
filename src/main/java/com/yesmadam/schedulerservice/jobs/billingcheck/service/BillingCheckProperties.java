package com.yesmadam.schedulerservice.jobs.billingcheck.service;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.Getter;
import lombok.Setter;

/** {@code billingcheck.*} settings, validated at startup so a bad value fails early (rule 21). */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "billingcheck")
public class BillingCheckProperties {

  /** Approved WhatsApp template name. */
  private String templateName;

  /** {@code tbl_order.category_id} values in scope. */
  private List<Integer> categoryIds = new ArrayList<>();

  /** Orders per page. */
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
