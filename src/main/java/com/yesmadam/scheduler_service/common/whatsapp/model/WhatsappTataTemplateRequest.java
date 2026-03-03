package com.yesmadam.scheduler_service.common.whatsapp.model;

import java.util.List;

import lombok.Data;

/** Template section of the Tata Communications WhatsApp request. */
@Data
public class WhatsappTataTemplateRequest {
  private String name;
  private WhatsappTemplateLanguageRequest language;
  private List<WhatsappTemplateComponentRequest> components;
}
