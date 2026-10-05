package com.yesmadam.schedulerservice.jobs.ozuzuorder.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.OzuzuOrder;

/** Access to {@code tbl_ozuzu_order}, keyed on the natural Unicommerce {@code code}. */
@Repository
public interface OzuzuOrderRepository extends JpaRepository<OzuzuOrder, Long> {

  /**
   * Batch existence/lookup for a set of codes — one query, mirroring messagingapis' {@code
   * commonService.findAllByPropertyIn(OzuzOrder.class, "code", orderCodes)}. The create and update
   * activities each call this once per run rather than once per code, exactly as the original did.
   *
   * <p>Access path: a full scan of {@code tbl_ozuzu_order} unless {@code code} already carries a
   * unique index — if not, {@code ALTER TABLE tbl_ozuzu_order ADD UNIQUE INDEX
   * uq_ozuzu_order_code (code)} (rule 15). The table is scoped to one low-volume Shopify channel,
   * so this has not yet been worth measuring; revisit once row counts are known.
   */
  List<OzuzuOrder> findByCodeIn(List<String> codes);
}
