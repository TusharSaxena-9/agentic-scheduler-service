package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * Mirrors tracking's {@code DocumentLine} exactly. This job only ever produces a single line
 * (the elite-fee item itself) and does not set {@code price}/{@code taxTotal}/{@code
 * sacEntry}/{@code hsnEntry}/{@code costingCode} — left at Java defaults, matching the original.
 */
@Data
public class EliteMismatchSyncDocumentLine {

  @JsonProperty("ItemCode")
  private String itemCode;

  @JsonProperty("Quantity")
  private double quantity;

  @JsonProperty("Price")
  private double price;

  @JsonProperty("Currency")
  private String currency;

  @JsonProperty("DiscountPercent")
  private double discountPercent;

  @JsonProperty("WarehouseCode")
  private String warehouseCode;

  @JsonProperty("BaseType")
  private Integer baseType;

  @JsonProperty("BaseEntry")
  private Integer baseEntry;

  @JsonProperty("BaseLine")
  private Integer baseLine;

  @JsonProperty("TaxCode")
  private String taxCode;

  @JsonProperty("FreeText")
  private String freeText;

  @JsonProperty("TaxTotal")
  private double taxTotal;

  @JsonProperty("UnitPrice")
  private double unitPrice;

  @JsonProperty("SACEntry")
  private Integer sacEntry;

  @JsonProperty("HSNEntry")
  private Integer hsnEntry;

  @JsonProperty("TreeType")
  private String treeType;

  @JsonProperty("CostingCode3")
  private String costingCode;

  @JsonProperty("LineTaxJurisdictions")
  private List<Object> lineTaxJurisdictions;
}
