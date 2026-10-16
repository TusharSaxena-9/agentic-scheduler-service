package com.yesmadam.schedulerservice.jobs.paymentrefund.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTataRequest;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * Adds {@code sourceType}, matching the shape {@code
 * jobs.order.feedbackreminder.whatsapp.FeedbackReminderWhatsappRequest} already sends to the same
 * Tata Omnichannel account.
 */
@Getter
@Setter
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentRefundWhatsappRequest extends WhatsappTataRequest {

  private String sourceType;
}
