package com.yesmadam.schedulerservice.jobs.spproductordersync.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.SpUnavailableProduct;

/**
 * Native bulk UPDATEs backing the SP delivered-unavailable-product sync job — copied verbatim from
 * messagingapis' {@code SpDaoImpl#incrementInventoryProductsPurchasedForDelivered} and {@code
 * SpDaoImpl#syncDeliveredSpUnavailableProducts}.
 *
 * <p>Native rather than HQL (rule 16): both statements {@code UPDATE} one table while joining
 * columns from {@code tbl_sp_order} (and, for the increment, an aggregated {@code
 * tbl_sp_order_products}) into the {@code SET}/{@code WHERE} clauses — a cross-table join inside an
 * {@code UPDATE} that JPQL's bulk-update form cannot express. There is also no JPA entity for {@code
 * tbl_sp_order}, {@code tbl_sp_order_products} or {@code tbl_sp_inventory} in {@code
 * ym-common-entity} to map one against even if HQL could.
 *
 * <p>Status literals are inlined rather than bound, matching this service's other native queries
 * (see {@link com.yesmadam.schedulerservice.repository.interfaces.SpRepository}), because
 * scheduler-service has no dependency on messagingapis' local enums. They map back to:
 *
 * <ul>
 *   <li>{@code 'ORDER_PLACED'} / {@code 'ORDER_DELIVERED'} — {@code
 *       SpUnavailableProductOrderStatus.ORDER_PLACED.getValue()} (status 2) and {@code
 *       .ORDER_DELIVERED.getValue()} (status 3, set alongside {@code order_status_code = 3})
 *   <li>{@code 3} / {@code 'ORDER DELIVERED'} — {@code SpOrderStatus.ORDER_DELIVERED.getStatus()}
 *       and {@code SpOrderStatus.findByValue(3)}; note the space, distinct from the
 *       underscore-separated unavailable-product status above
 * </ul>
 *
 * <p><b>Access path (rule 15):</b> both queries filter {@code tbl_sp_unavailable_products} on
 * {@code (is_available, order_status)} and join to {@code tbl_sp_order} on its primary key {@code
 * id}. This assumes a composite index on {@code (is_available, order_status)} — not verified
 * against the live schema as of this migration; confirm before enabling the schedule; a full scan
 * here runs on every fire (every 2 hours) rather than once.
 */
@Repository
public interface SpUnavailableProductSyncRepository
    extends JpaRepository<SpUnavailableProduct, Integer> {

  /**
   * Flips delivered-but-unavailable product rows to available/delivered.
   *
   * @return rows updated in {@code tbl_sp_unavailable_products}
   */
  @Modifying
  @Query(
      value =
          """
          update ysmdm_admin.tbl_sp_unavailable_products u
          join ysmdm_admin.tbl_sp_order o on o.id = u.sp_order_id
          set u.is_available = 1,
              u.order_status = 'ORDER_DELIVERED',
              u.order_status_code = 3
          where u.is_available = 0
            and u.order_status = 'ORDER_PLACED'
            and u.sp_order_id is not null
            and (o.order_status_code = 3 or o.order_status = 'ORDER DELIVERED')
          """,
      nativeQuery = true)
  int syncDeliveredSpUnavailableProducts();

  /**
   * Increments SP inventory counters ({@code products_purchased}, {@code stock_on_hand}, {@code
   * total_quantity_with_sp}) for products delivered while still marked unavailable. Joins order
   * products to sum actual {@code product_quantity}, not just row count. Must run before {@link
   * #syncDeliveredSpUnavailableProducts()} — it reads the exact rows that method flips, and is not
   * idempotent under retry (see the workflow's activity-options javadoc).
   *
   * @return rows updated in {@code tbl_sp_inventory}
   */
  @Modifying
  @Query(
      value =
          """
          update ysmdm_admin.tbl_sp_inventory i
          join (
            select u.sp_id, u.product_id, sum(op.product_quantity) as total_qty
            from ysmdm_admin.tbl_sp_unavailable_products u
            join ysmdm_admin.tbl_sp_order o on o.id = u.sp_order_id
            join ysmdm_admin.tbl_sp_order_products op
              on op.order_id = u.sp_order_id and op.product_id = u.product_id
            where u.is_available = 0
              and u.order_status = 'ORDER_PLACED'
              and u.sp_order_id is not null
              and (o.order_status_code = 3 or o.order_status = 'ORDER DELIVERED')
            group by u.sp_id, u.product_id
          ) agg on agg.sp_id = i.sp_id and agg.product_id = i.product_id
          set i.products_purchased = coalesce(i.products_purchased, 0) + agg.total_qty,
              i.stock_on_hand = coalesce(i.stock_on_hand, 0) + agg.total_qty,
              i.total_quantity_with_sp = coalesce(i.total_quantity_with_sp, 0) + agg.total_qty
          """,
      nativeQuery = true)
  int incrementInventoryProductsPurchasedForDelivered();
}
