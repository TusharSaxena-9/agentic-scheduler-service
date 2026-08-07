package com.yesmadam.schedulerservice.common.whatsapp.model;

import java.util.List;

import lombok.Data;

/** Component (body/header/footer) section of a WhatsApp template request. */
@Data
public class WhatsappTemplateComponentRequest {
  private String type;
  private List<WhatsappComponentParameterRequest> parameters;
}
