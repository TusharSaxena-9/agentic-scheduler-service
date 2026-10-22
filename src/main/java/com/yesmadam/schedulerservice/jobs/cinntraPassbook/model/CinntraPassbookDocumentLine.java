package com.yesmadam.schedulerservice.jobs.cinntraPassbook.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

/**
 * One invoice line. Copy of tracking's {@code req.DocumentLine} — no {@code NON_NULL}, so the
 * fields this job never sets still go out as {@code null}/{@code 0}, as they do from tracking.
 */
@Getter
@Setter
public class CinntraPassbookDocumentLine {

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

  /** Never set by this job; always serialized as {@code null}, as tracking's is. */
  @JsonProperty("LineTaxJurisdictions")
  private List<Object> lineTaxJurisdictions;
}
