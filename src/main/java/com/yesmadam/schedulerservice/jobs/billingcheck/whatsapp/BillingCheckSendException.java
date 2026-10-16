package com.yesmadam.schedulerservice.jobs.billingcheck.whatsapp;

/** A provider send failed; the caller logs it and moves on. */
public class BillingCheckSendException extends RuntimeException {

  public BillingCheckSendException(String message, Throwable cause) {
    super(message, cause);
  }
}
