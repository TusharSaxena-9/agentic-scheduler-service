package com.yesmadam.schedulerservice.jobs.raindelay;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One customer to warn. Only the three fields the push needs — the copy is static, so no name,
 * mobile, hub or slot time is carried.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RainDelayTarget {

  private Long orderId;
  private Integer hubId;

  /** CleverTap identity. */
  private Long userId;
}
