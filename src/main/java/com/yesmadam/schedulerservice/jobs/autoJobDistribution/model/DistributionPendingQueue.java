package com.yesmadam.schedulerservice.jobs.autoJobDistribution.model;

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
 * The DB-backed waiting pool of orders eligible for distribution but not yet fired, in {@code
 * tbl_distribution_pending_queue}.
 *
 * <p>{@code is_pending} is tri-state, and the states are not interchangeable:
 *
 * <pre>
 * 1     waiting to be fired
 * NULL  fired — a live round exists for this order
 * 0     resolved — that round reached a terminal state, or the row was discarded as stale
 * </pre>
 *
 * <p><b>How "one waiting row per order" is actually enforced</b> (verified against the UAT schema
 * on 2026-08-31, because the mechanism is not what it looks like). The table carries a stored
 * generated column:
 *
 * <pre>
 * pending_order_id bigint GENERATED ALWAYS AS
 *     (CASE WHEN is_pending = 1 THEN order_id ELSE NULL END) STORED
 * UNIQUE KEY uq_pending_order_active (pending_order_id)
 * </pre>
 *
 * <p>So the unique constraint applies <em>only while a row is waiting</em>: once {@code is_pending}
 * becomes NULL (fired) or 0 (resolved), the generated column goes NULL and MySQL stops constraining
 * it. That is what lets one order accumulate many historical rows while never having two waiting at
 * once, and it is what makes the enqueue {@code INSERT IGNORE} race-safe.
 *
 * <p>It is <em>not</em> a composite key on {@code (order_id, is_pending)} — that would have allowed
 * only a single resolved row per order and broken re-queuing. Do not "correct" the schema toward
 * one.
 *
 * <p>{@code pending_order_id} is deliberately not mapped here: it is generated, so any attempt to
 * insert or update it is an error.
 */
@Data
@Entity
@Table(name = "tbl_distribution_pending_queue", catalog = DBConstants.YESMADAM_USERS)
public class DistributionPendingQueue {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "order_id")
  private Long orderId;

  /** {@code 1} waiting, {@code NULL} fired, {@code 0} resolved — see the class note. */
  @Column(name = "is_pending")
  private Integer isPending;

  @Column(name = "distributed_at")
  private Timestamp distributedAt;

  @Column(name = "created_at")
  private Timestamp createdAt;

  @Column(name = "updated_at")
  private Timestamp updatedAt;
}
