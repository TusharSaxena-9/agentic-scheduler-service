package com.yesmadam.schedulerservice.jobs.couponwhatsapp.service;

import org.springframework.stereotype.Component;

import com.amazonaws.services.sqs.AmazonSQSAsync;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yesmadam.dto.CouponWhatsappMessage;

import lombok.extern.slf4j.Slf4j;

/**
 * Publishes one coupon-WhatsApp candidate to SQS — expected daily volume doesn't justify {@code
 * SendMessageBatch}'s extra bookkeeping. Any send failure throws; the caller decides what to do
 * with claims sent before the failure (see {@link CouponWhatsappReminderWriter}).
 */
@Slf4j
@Component
public class CouponWhatsappSqsProducer {

  private final AmazonSQSAsync amazonSqs;
  private final ObjectMapper objectMapper;

  public CouponWhatsappSqsProducer(AmazonSQSAsync amazonSqs, ObjectMapper objectMapper) {
    this.amazonSqs = amazonSqs;
    this.objectMapper = objectMapper;
  }

  public void sendOne(String queueUrl, CouponWhatsappMessage message) {
    try {
      amazonSqs.sendMessage(queueUrl, toJson(message));
      log.info("event=coupon_whatsapp_enqueue_send_succeeded queue={} claim_id={}", queueUrl, message.getClaimId());
    } catch (RuntimeException ex) {
      log.error(
          "event=coupon_whatsapp_enqueue_send_failed queue={} claim_id={} error={}",
          queueUrl, message.getClaimId(), ex.getMessage());
      throw ex;
    }
  }

  private String toJson(CouponWhatsappMessage message) {
    try {
      return objectMapper.writeValueAsString(message);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize " + message, e);
    }
  }
}
