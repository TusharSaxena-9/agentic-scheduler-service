package com.yesmadam.schedulerservice.jobs.cinntrapassbook.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * One {@code BPAddresses} element of the address-retry PATCH. Copy of tracking's {@code
 * req.BPAddress}.
 */
@Data
public class CinntraPassbookBpAddress {

  @JsonProperty("AddressName")
  private String addressName;

  @JsonProperty("Street")
  private String street;

  @JsonProperty("Block")
  private String block;

  @JsonProperty("ZipCode")
  private Integer zipCode;

  @JsonProperty("City")
  private String city;

  @JsonProperty("County")
  private String county;

  @JsonProperty("Country")
  private String country;

  @JsonProperty("State")
  private String state;

  @JsonProperty("AddressType")
  private String addressType;

  @JsonProperty("BuildingFloorRoom")
  private String buildingFloorRoom;

  @JsonProperty("GSTIN")
  private String gstin;

  @JsonProperty("GstType")
  private String gstType;

  @JsonProperty("BPCode")
  private Integer bpCode;
}
