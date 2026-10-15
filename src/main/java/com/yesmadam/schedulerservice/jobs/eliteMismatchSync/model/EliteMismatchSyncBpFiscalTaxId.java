package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/** Mirrors tracking's {@code BPFiscalTaxID}. Only {@code taxId0}/{@code taxId2}/{@code taxId3}/{@code addrType} are set by this job. */
@Data
public class EliteMismatchSyncBpFiscalTaxId {

  @JsonProperty("TaxId0")
  private String taxId0;

  @JsonProperty("TaxId2")
  private String taxId2;

  @JsonProperty("TaxId3")
  private String taxId3;

  @JsonProperty("TaxId4")
  private String taxId4;

  @JsonProperty("TaxId5")
  private String taxId5;

  @JsonProperty("TaxId6")
  private String taxId6;

  @JsonProperty("TaxId7")
  private String taxId7;

  @JsonProperty("TaxId8")
  private String taxId8;

  @JsonProperty("TaxId9")
  private String taxId9;

  @JsonProperty("TaxId10")
  private String taxId10;

  @JsonProperty("TaxId11")
  private String taxId11;

  @JsonProperty("AddrType")
  private String addrType;

  @JsonProperty("TaxId12")
  private String taxId12;

  @JsonProperty("TaxId13")
  private String taxId13;
}
