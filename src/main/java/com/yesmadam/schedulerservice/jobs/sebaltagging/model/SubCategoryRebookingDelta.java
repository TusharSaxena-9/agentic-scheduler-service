package com.yesmadam.schedulerservice.jobs.sebaltagging.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One (SP, sub-category) row's contribution from a single date-range chunk: how many qualifying
 * first-orders that SP served in that sub-category within the chunk's window, and how many of
 * those users rebooked (completed/feedback-done) within 90 days. Added onto the running totals in
 * {@code tbl_sp_sebal_subcategory_tags}, never used to replace them directly.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubCategoryRebookingDelta {

  private Integer spId;
  private Integer subCategoryId;
  private long firstJobUsersCount;
  private long rebooked90dUsersCount;
}
