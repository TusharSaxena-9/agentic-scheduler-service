package com.yesmadam.schedulerservice.common.whatsapp.model;

import lombok.Data;

/** Root request model for Tata Communications WhatsApp API. */
@Data
public class WhatsappTataRequest {
  private String to;
  private String type;
  private String source;
  private WhatsappTataTemplateRequest template;
}
