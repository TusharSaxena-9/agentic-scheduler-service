package com.yesmadam.schedulerservice.jobs.billingcheck.whatsapp;

/** A provider send failed. The caller logs it and moves on to the next customer. */
public class BillingCheckSendException extends RuntimeException {

  public BillingCheckSendException(String message, Throwable cause) {
    super(message, cause);
  }
}
