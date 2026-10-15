package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/** Mirrors tracking's {@code BPAddress}. Only the fields this job's {@code buildBpAddress}/{@code applyAddressFields} set are populated. */
@Data
public class EliteMismatchSyncBpAddress {

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
