package com.yesmadam.schedulerservice.jobs.walletexpiryreminder.model;

import java.sql.Timestamp;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One day-offset's target window (30 / 7 / 4 / 1 days before wallet expiry), computed once per
 * run by {@code WalletExpiryReminderActivities#computeRunWindows}.
 *
 * <p>Computed in the activity, never in workflow code — {@code LocalDate.now()} is a clock read
 * and does not belong in replayed workflow code (CLAUDE.md rule 1). The workflow only ever holds
 * this already-computed, explicit-zone window.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WalletExpiryRunWindow {

  /** 30, 7, 4 or 1 — days before {@link #walletExpireDate} this reminder is for. */
  private int dayOffset;

  /** Inclusive start of the target day, Asia/Kolkata. */
  private Timestamp dayStart;

  /** Exclusive end of the target day (start of the next day), Asia/Kolkata. */
  private Timestamp dayEnd;

  /** Calendar date the reminder is about — carried through for logging only. */
  private LocalDate walletExpireDate;
}
