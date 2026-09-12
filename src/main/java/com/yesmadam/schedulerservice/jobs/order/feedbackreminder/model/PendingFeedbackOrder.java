package com.yesmadam.schedulerservice.jobs.order.feedbackreminder.model;

import java.io.Serializable;
import java.sql.Timestamp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PendingFeedbackOrder implements Serializable {

  private Long orderId;
  private Long mobile;
  private String customerName;
  private Timestamp orderStartTime;
  private String serviceNames;
}
