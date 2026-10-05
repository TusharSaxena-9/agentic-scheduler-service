package com.yesmadam.schedulerservice.jobs.commissionaudit.projections;

/** One flagged {@code tbl_order} row as returned by the commission audit query. */
public interface CommissionAuditProjection {

  Long getOrderId();

  /** {@code tbl_order.s_p_assign_by_user}. NULL or 0 means no SP was assigned. */
  Integer getSpId();

  String getReason();
}
