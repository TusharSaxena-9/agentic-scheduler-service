package com.yesmadam.schedulerservice.jobs.commissionaudit.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One flagged order, as a plain serializable value.
 *
 * <p>Separate from {@code CommissionAuditProjection} because this crosses the Temporal activity
 * boundary and gets stored in workflow history. A Hibernate projection proxy is not
 * JSON-serializable.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommissionAuditRow {

  private Long orderId;

  /** {@code s_p_assign_by_user}. NULL means the order was completed with no SP assigned. */
  private Integer spId;

  private String reason;
}
