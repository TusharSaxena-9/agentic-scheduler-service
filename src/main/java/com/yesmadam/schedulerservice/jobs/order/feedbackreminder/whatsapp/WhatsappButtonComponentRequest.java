package com.yesmadam.schedulerservice.jobs.order.feedbackreminder.whatsapp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTemplateComponentRequest;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WhatsappButtonComponentRequest extends WhatsappTemplateComponentRequest {

  @JsonProperty("sub_type")
  private String subType;

  private String index;
}
