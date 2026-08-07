package com.yesmadam.schedulerservice.common.whatsapp.model;

import lombok.Data;

/** Language configuration for a WhatsApp template request. */
@Data
public class WhatsappTemplateLanguageRequest {
  private String code;

  public WhatsappTemplateLanguageRequest(String code) {
    this.code = code;
  }
}
