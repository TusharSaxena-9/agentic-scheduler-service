package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model;

import java.sql.Timestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/**
 * {@code tbl_product_kit_assign_to_sp} — one row per kit assigned to an SP. {@code approvedat} is
 * set when the assignment is approved, and is the only column this job reads it for.
 *
 * <p>Declared here rather than taken from {@code ym-common-entity}, which has no mapping for this
 * table (it carries {@code KitMaster} and {@code KitMasterProduct} but not the assignment row).
 * admin's {@code com.ysmdm.productAndinventory.persistance.ProductKitAssignToSP} is the canonical
 * mapping; this is a deliberately narrow copy of it — only the columns the eligibility query needs,
 * so nothing unbounded rides along on a fetch (CLAUDE.md rule 9). If this table's mapping is ever
 * promoted into {@code ym-common-entity}, delete this class and point the repository at that one.
 *
 * <p>The job never loads this entity. It exists so {@link
 * com.yesmadam.schedulerservice.jobs.spavailabilityautoon.repository.ProductKitApprovalRepository}
 * has a managed type to anchor to and can express its query in HQL (rule 16); the query itself
 * projects scalar {@code sp_id}s.
 */
@Data
@Entity
@Table(name = "tbl_product_kit_assign_to_sp", catalog = DBConstants.YESMADAM_ADMIN)
public class ProductKitAssignToSp {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @Column(name = "sp_id")
  private Integer spId;

  @Column(name = "kitid")
  private Integer kitId;

  /** Null until the assignment is approved. This job's whole window filter is on this column. */
  @Column(name = "approvedat")
  private Timestamp approvedAt;
}
