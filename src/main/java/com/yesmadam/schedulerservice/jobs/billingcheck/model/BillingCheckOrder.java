package com.yesmadam.schedulerservice.jobs.billingcheck.model;

import java.io.Serializable;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One message to send: recipient, order id (for logs) and the template body variables. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BillingCheckOrder implements Serializable {

  private Long orderId;
  private Long mobile;

  /** {{1}} first name, {{2}} category, {{3}} final amount, {{4}} billed services. */
  private List<String> bodyParams;
}
