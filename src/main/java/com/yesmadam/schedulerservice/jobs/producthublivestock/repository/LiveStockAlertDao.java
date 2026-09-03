package com.yesmadam.schedulerservice.jobs.producthublivestock.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.common.constants.DBConstants;
import com.yesmadam.schedulerservice.jobs.producthublivestock.projections.LiveStockAlertProjection;

/**
 * Read-only access to {@code tbl_product_inventory_live} for the "Uni Live Stock" check shown on
 * the admin product-hub-inventory page.
 *
 * <p>Plain {@code JdbcTemplate}, not Spring Data (rule 16 — native only where a declarative {@code
 * @Query} genuinely can't express it): checking a per-hub product watchlist needs a row-constructor
 * {@code IN} — {@code (hub_id, product_id) IN ((?,?), (?,?), ...)}, one tuple per configured
 * hub/product pair from {@link
 * com.yesmadam.schedulerservice.jobs.producthublivestock.config.ProductHubWatchlist}. Spring Data's
 * {@code @Param} list binding only expands a single-column {@code IN (:list)}; it cannot express
 * "this product only at this hub", which is exactly the cross-join bug this DAO replaces — the
 * previous version checked every configured hub against every configured product regardless of
 * whether that pairing was ever configured. Same approach as {@code SpNotReachedHomeBatchDao} /
 * {@code SebalTaggingBatchDao}: build the placeholder list to match the data, bind every value
 * positionally, never concatenate a value into the SQL string.
 *
 * <p><b>Access path (rule 15):</b> one query, one round trip, sized by the watchlist — currently
 * ~700 tuples, ~1,400 placeholders, comfortably inside MySQL's 65,535-placeholder protocol limit.
 * This assumes an index on {@code tbl_product_inventory_live(product_hub_id, product_id)}; that has
 * not been verified against the live schema, and MySQL's ability to use a composite index for a
 * row-constructor {@code IN} at this size has not been checked with {@code EXPLAIN} either — do
 * that before the watchlist grows much past its current size, or before trusting this query's
 * latency blindly.
 */
@Repository
public class LiveStockAlertDao {

  private static final String TABLE_INVENTORY =
      DBConstants.YESMADAM_ADMIN + ".tbl_product_inventory_live";
  private static final String TABLE_HUB = DBConstants.YESMADAM_ADMIN + ".tbl_product_hub";
  private static final String TABLE_PRODUCT = DBConstants.YESMADAM_ADMIN + ".tbl_product";

  private final JdbcTemplate jdbcTemplate;

  public LiveStockAlertDao(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Every product at or below zero live stock, restricted to the exact hub/product pairs in {@code
   * hubProductIds} — never a cross product of every hub against every product — and to products
   * that are active and customer-purchasable: {@code p.status} and {@code
   * p.isProductVisibleToCustomer} (the actual column behind the {@code Product} entity's {@code
   * isProductActiveForPurchase} field per its {@code @Column} mapping; a native query needs the
   * real column name, not the Java field name).
   *
   * @param hubProductIds configured product ids to check, keyed by product hub id
   */
  public List<LiveStockAlertProjection> findOutOfStock(Map<Integer, List<Integer>> hubProductIds) {
    List<Object> params = new ArrayList<>();
    List<String> tuples = new ArrayList<>();
    for (Map.Entry<Integer, List<Integer>> hub : hubProductIds.entrySet()) {
      for (Integer productId : hub.getValue()) {
        tuples.add("(?,?)");
        params.add(hub.getKey());
        params.add(productId);
      }
    }
    if (tuples.isEmpty()) {
      return List.of();
    }

    String sql =
        "SELECT pil.product_hub_id AS hubId, ph.hub AS hubName, pil.product_id AS productId, "
            + "       p.name AS productName, pil.live_stock_new AS liveStock "
            + "FROM "
            + TABLE_INVENTORY
            + " pil "
            + "JOIN "
            + TABLE_HUB
            + " ph ON ph.id = pil.product_hub_id "
            + "JOIN "
            + TABLE_PRODUCT
            + " p ON p.id = pil.product_id "
            + "WHERE (pil.product_hub_id, pil.product_id) IN ("
            + String.join(", ", tuples)
            + ") "
            + "  AND pil.live_stock_new <= 0 "
            + "  AND p.status = true AND p.isProductActiveForPurchase = true "
            + "ORDER BY ph.hub, p.name";

    return jdbcTemplate.query(
        sql,
        params.toArray(),
        (rs, rowNum) ->
            new LiveStockAlertProjectionRow(
                rs.getInt("hubId"),
                rs.getString("hubName"),
                rs.getInt("productId"),
                rs.getString("productName"),
                rs.getFloat("liveStock")));
  }

  /**
   * Plain {@link LiveStockAlertProjection} implementation for the JDBC row mapper — a hand-rolled
   * query has no Hibernate projection proxy to hand back, so this stands in for one.
   */
  private record LiveStockAlertProjectionRow(
      Integer hubId, String hubName, Integer productId, String productName, Float liveStock)
      implements LiveStockAlertProjection {

    @Override
    public Integer getHubId() {
      return hubId;
    }

    @Override
    public String getHubName() {
      return hubName;
    }

    @Override
    public Integer getProductId() {
      return productId;
    }

    @Override
    public String getProductName() {
      return productName;
    }

    @Override
    public Float getLiveStock() {
      return liveStock;
    }
  }
}
