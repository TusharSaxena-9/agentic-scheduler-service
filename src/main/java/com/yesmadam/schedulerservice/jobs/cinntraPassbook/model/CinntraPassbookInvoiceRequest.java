package com.yesmadam.schedulerservice.jobs.cinntraPassbook.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

/** SAP {@code Invoices} / {@code Drafts} body. Copy of tracking's {@code req.PurchaseInvoiceRequest}. */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CinntraPassbookInvoiceRequest {

  @JsonProperty("DocType")
  private String docType;

  @JsonProperty("DocDate")
  private String docDate;

  @JsonProperty("DocDueDate")
  private String docDueDate;

  @JsonProperty("CardCode")
  private String cardCode;

  @JsonProperty("NumAtCard")
  private String numAtCard;

  @JsonProperty("Comments")
  private String comments;

  @JsonProperty("Series")
  private int series;

  @JsonProperty("TaxDate")
  private String taxDate;

  @JsonProperty("DocObjectCode")
  private String docObjectCode;

  @JsonProperty("ShipToCode")
  private String shipToCode;

  @JsonProperty("PayToCode")
  private String payToCode;

  @JsonProperty("OriginalRefNo")
  private String originalRefNo;

  @JsonProperty("OriginalRefDate")
  private String originalRefDate;

  @JsonProperty("GSTTransactionType")
  private String gstTransactionType;

  @JsonProperty("BPL_IDAssignedToInvoice")
  private int bpl_IDAssignedToInvoice;

  @JsonProperty("U_TOS")
  private String utos;

  @JsonProperty("U_ON")
  private Long uon;

  @JsonProperty("U_Consignee")
  private String uconsignee;

  @JsonProperty("U_VNO")
  private Long voucherNumber;

  @JsonProperty("DocumentLines")
  private List<CinntraPassbookDocumentLine> documentLines;
}
