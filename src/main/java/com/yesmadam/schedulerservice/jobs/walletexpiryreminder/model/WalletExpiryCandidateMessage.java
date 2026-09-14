package com.yesmadam.schedulerservice.jobs.walletexpiryreminder.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Message body published to the single {@code wallet-expiry-notification} SQS queue — one
 * candidate, serialized to JSON. {@link #dayOffset} is carried in the payload since all four
 * day-offsets share one queue. Carries {@code mobile}/{@code walletMin}/{@code
 * amountLimit}/{@code expireAt} as an enqueue-time snapshot so the consumer can send without its
 * own {@code tbl_user} read. {@link #expireAt} is epoch millis rather than {@code Timestamp}/
 * {@code Date} so JSON (de)serialization doesn't depend on the producer's and consumer's Jackson
 * defaults agreeing.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WalletExpiryCandidateMessage {

  private Long userId;

  /** 30, 7, 4 or 1 — days before wallet expiry this reminder is for. */
  private int dayOffset;

  private Long mobile;

  private Integer walletMin;

  private Integer amountLimit;

  /** Epoch millis — see class javadoc for why this isn't a Timestamp/Date. */
  private Long expireAt;
}
