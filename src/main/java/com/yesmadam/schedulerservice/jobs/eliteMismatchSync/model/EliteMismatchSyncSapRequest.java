package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * Mirrors tracking's {@code SapRequest} — the BusinessPartner (customer) creation payload. Only
 * a subset of fields are set by this job's {@code buildCustomerRequest} — {@code
 * valid}/{@code validFrom}/{@code validTo}/{@code validRemarks}/{@code frozen}/{@code
 * frozenFrom}/{@code frozenTo}/{@code frozenRemarks}/{@code debitorAccount} are left null,
 * matching the original.
 */
@Data
public class EliteMismatchSyncSapRequest {

  @JsonProperty("CardCode")
  private String cardCode;

  @JsonProperty("CardName")
  private String cardName;

  @JsonProperty("CardType")
  private String cardType;

  @JsonProperty("GroupCode")
  private int groupCode;

  @JsonProperty("Phone1")
  private String phone1;

  @JsonProperty("PayTermsGrpCode")
  private int payTermsGrpCode;

  @JsonProperty("Currency")
  private String currency;

  @JsonProperty("ContactPerson")
  private String contactPerson;

  @JsonProperty("Cellular")
  private String cellular;

  @JsonProperty("EmailAddress")
  private String emailAddress;

  @JsonProperty("Valid")
  private String valid;

  @JsonProperty("ValidFrom")
  private String validFrom;

  @JsonProperty("ValidTo")
  private String validTo;

  @JsonProperty("ValidRemarks")
  private String validRemarks;

  @JsonProperty("Frozen")
  private String frozen;

  @JsonProperty("UseBillToAddrToDetermineTax")
  private String useBillToAddrToDetermineTax;

  @JsonProperty("FrozenFrom")
  private String frozenFrom;

  @JsonProperty("FrozenTo")
  private String frozenTo;

  @JsonProperty("FrozenRemarks")
  private String frozenRemarks;

  @JsonProperty("TaxExemptionLetterNum")
  private String taxExemptionLetterNum;

  @JsonProperty("DebitorAccount")
  private String debitorAccount;

  @JsonProperty("Series")
  private int series;

  @JsonProperty("ContactEmployees")
  private List<EliteMismatchSyncContactEmployee> contactEmployees;

  @JsonProperty("BPAddresses")
  private List<EliteMismatchSyncBpAddress> bpAddresses;

  @JsonProperty("BPFiscalTaxIDCollection")
  private List<EliteMismatchSyncBpFiscalTaxId> bpFiscalTaxIDCollection;
}
