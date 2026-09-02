package com.yesmadam.schedulerservice.jobs.producthublivestock.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.ProductInventoryLiveStock;
import com.yesmadam.schedulerservice.common.constants.DBConstants;
import com.yesmadam.schedulerservice.jobs.producthublivestock.projections.LiveStockAlertProjection;

/**
 * Read-only access to {@code tbl_product_inventory_live} for the "Uni Live Stock" check shown on
 * the admin product-hub-inventory page.
 *
 * <p>Native, not HQL (rule 16): the shared {@link ProductInventoryLiveStock} entity only maps {@code
 * live_stock} (used elsewhere for the Sokora availability check), not {@code live_stock_new} — the
 * column this job needs — and there is no shared entity for {@code tbl_product_hub} to join against.
 * {@code JpaRepository<ProductInventoryLiveStock, Integer>} is used only for the generic type; the
 * query result is mapped by column alias to {@link LiveStockAlertProjection}, independent of that
 * entity's declared fields.
 *
 * <p><b>Access path (rule 15):</b> filtered by {@code product_hub_id IN (...)} and {@code
 * product_id IN (...)}, both bounded by the curated {@code tbl_settings} lists in {@link
 * com.yesmadam.schedulerservice.jobs.producthublivestock.enums.ProductHubLiveStockAlertConfig} —
 * expected to be tens of ids, not thousands, so the {@code IN}-list cost documented in {@code
 * SebalTaggingCohortRepository} (full scan past ~20k ids) should not apply here. This assumes an
 * index on {@code tbl_product_inventory_live(product_hub_id, product_id)}; that has not been
 * verified against the live schema — check it before raising the id-list size much beyond a few
 * hundred.
 */
@Repository
public interface LiveStockAlertRepository extends JpaRepository<ProductInventoryLiveStock, Integer> {

  /**
   * Every configured hub/product combination whose live stock is at or below zero, restricted to
   * products that are active and customer-purchasable — {@code p.status} and {@code
   * p.isProductVisibleToCustomer} (the actual column behind the {@code Product} entity's {@code
   * isProductActiveForPurchase} field per its {@code @Column} mapping; a native query needs the real
   * column name, not the Java field name).
   *
   * @param hubIds configured product-hub ids to check
   * @param productIds configured product ids to check
   */
  @Query(
      value =
          "SELECT pil.product_hub_id AS hubId, ph.hub AS hubName, pil.product_id AS productId, "
              + "       p.name AS productName, pil.live_stock_new AS liveStock "
              + "FROM "
              + DBConstants.YESMADAM_ADMIN
              + ".tbl_product_inventory_live pil "
              + "JOIN "
              + DBConstants.YESMADAM_ADMIN
              + ".tbl_product_hub ph ON ph.id = pil.product_hub_id "
              + "JOIN "
              + DBConstants.YESMADAM_ADMIN
              + ".tbl_product p ON p.id = pil.product_id "
              + "WHERE pil.product_hub_id IN (:hubIds) AND pil.product_id IN (:productIds) "
              + "  AND pil.live_stock_new <= 0 "
              + "  AND p.status = true AND p.isProductActiveForPurchase = true "
              + "ORDER BY ph.hub, p.name",
      nativeQuery = true)
  List<LiveStockAlertProjection> findOutOfStock(
      @Param("hubIds") List<Integer> hubIds, @Param("productIds") List<Integer> productIds);
}
