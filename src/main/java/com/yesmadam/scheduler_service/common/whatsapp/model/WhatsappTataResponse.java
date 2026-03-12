package com.yesmadam.scheduler_service.common.whatsapp.model;

import lombok.Data;

/** Response model from Tata Communications WhatsApp API. */
@Data
public class WhatsappTataResponse {
  private String id;
  private String status;
}
