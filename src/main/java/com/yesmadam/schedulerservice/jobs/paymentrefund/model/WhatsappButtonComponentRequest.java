package com.yesmadam.schedulerservice.jobs.paymentrefund.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTemplateComponentRequest;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * A template "button" component ({@code sub_type: URL}) — same shape as the existing job-local
 * {@code jobs.order.feedbackreminder.whatsapp.WhatsappButtonComponentRequest}, duplicated here
 * rather than depended on across job packages, so this job stays self-contained.
 */
@Getter
@Setter
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WhatsappButtonComponentRequest extends WhatsappTemplateComponentRequest {

  @JsonProperty("sub_type")
  private String subType;

  private String index;
}
