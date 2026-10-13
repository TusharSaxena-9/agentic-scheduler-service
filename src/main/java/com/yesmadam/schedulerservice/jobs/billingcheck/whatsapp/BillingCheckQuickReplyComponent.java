package com.yesmadam.schedulerservice.jobs.billingcheck.whatsapp;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappComponentParameterRequest;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTemplateComponentRequest;

import lombok.Getter;
import lombok.Setter;

/**
 * A template quick-reply button: {@code {"type":"button","sub_type":"quick_reply","index":"0",
 * "parameters":[{"type":"payload","payload":"..."}]}}. The payload is what the customer's tap
 * echoes back to the inbound handler, which is how a Yes/No reply is tied to a booking.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BillingCheckQuickReplyComponent extends WhatsappTemplateComponentRequest {

  @JsonProperty("sub_type")
  private String subType = "quick_reply";

  private String index;

  public BillingCheckQuickReplyComponent(String index, String payload) {
    setType("button");
    this.index = index;
    setParameters(List.of(new PayloadParameter(payload)));
  }

  /** {@code text} is null and omitted from the JSON; only {@code payload} is sent. */
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
