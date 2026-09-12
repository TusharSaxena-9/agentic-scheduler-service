package com.yesmadam.schedulerservice.jobs.order.feedbackreminder.whatsapp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTataRequest;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FeedbackReminderWhatsappRequest extends WhatsappTataRequest {

  private String sourceType;
}
