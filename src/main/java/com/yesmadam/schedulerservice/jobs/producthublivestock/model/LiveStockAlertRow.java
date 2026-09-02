package com.yesmadam.schedulerservice.jobs.producthublivestock.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One flagged hub/product combination, as a plain serializable value.
 *
 * <p>Separate from {@code LiveStockAlertProjection} because this crosses the Temporal activity
 * boundary and gets stored in workflow history — it has to be JSON-serializable, which a Hibernate
 * projection proxy is not.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LiveStockAlertRow {

  private Integer hubId;
  private String hubName;
  private Integer productId;
  private String productName;
  private Float liveStock;
}
