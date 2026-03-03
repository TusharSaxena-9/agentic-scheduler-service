package com.yesmadam.scheduler_service.common.whatsapp.model;

import lombok.Data;

/** Individual parameter within a WhatsApp template component. */
@Data
public class WhatsappComponentParameterRequest {
  private String type;
  private String text;

  public WhatsappComponentParameterRequest(String type, String text) {
    this.type = type;
    this.text = text;
  }
}
