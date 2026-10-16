package com.yesmadam.schedulerservice.jobs.billingcheck.whatsapp;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappComponentParameterRequest;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTemplateComponentRequest;

import lombok.Getter;
import lombok.Setter;

/**
 * A quick-reply button. {@code sub_type} is upper-case and the payload is a Tata-issued id, both as
 * in Tata's working request.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BillingCheckQuickReplyComponent extends WhatsappTemplateComponentRequest {

  @JsonProperty("sub_type")
  private String subType = "QUICK_REPLY";

  private String index;

  public BillingCheckQuickReplyComponent(String index, String payload) {
    setType("button");
    this.index = index;
    setParameters(List.of(new PayloadParameter(payload)));
  }

  /** {@code text} is null and omitted from the JSON. */
  @Getter
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class PayloadParameter extends WhatsappComponentParameterRequest {

    private final String payload;

    PayloadParameter(String payload) {
      super("payload", null);
      this.payload = payload;
    }
  }
}
