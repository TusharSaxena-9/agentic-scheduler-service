package com.yesmadam.schedulerservice.jobs.paymentrefund.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Wire shape app-server's {@code /userapi/otp/verification} endpoint expects. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OtpVerificationRequest {

  private Long mobile;
  private Integer otp;
}
