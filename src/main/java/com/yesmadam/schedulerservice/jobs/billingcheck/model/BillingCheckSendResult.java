package com.yesmadam.schedulerservice.jobs.billingcheck.model;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BillingCheckSendResult implements Serializable {

  private int sent;
  private int failed;
}
