package com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/** The line-item fields {@code OzuzuOrderWriter} maps onto an {@code OzuzuOrderProduct}. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UnicommerceSaleOrderItem {

  private Long id;
  private String itemSku;
  private String itemName;
  private Double maxRetailPrice;
  private Double discount;
  private Double sellingPrice;
  private String hsnCode;
  private Double sellingPriceWithoutTaxesAndDiscount;
  private Double totalIntegratedGst;
  private Double totalCentralGst;
  private Double totalStateGst;
  private String statusCode;
  private String shippingPackageStatus;
  private String facilityName;
}
