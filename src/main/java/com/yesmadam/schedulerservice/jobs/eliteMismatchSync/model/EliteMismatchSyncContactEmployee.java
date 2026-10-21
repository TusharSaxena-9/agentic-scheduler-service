package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/** Mirrors tracking's {@code ContactEmployee}. */
@Data
public class EliteMismatchSyncContactEmployee {

  @JsonProperty("Name")
  private String name;

  @JsonProperty("Phone1")
  private String phone1;

  @JsonProperty("MobilePhone")
  private String mobilePhone;

  @JsonProperty("E_Mail")
  private String email;

  @JsonProperty("PlaceOfBirth")
  private String placeOfBirth;

  @JsonProperty("DateOfBirth")
  private String dateOfBirth;

  @JsonProperty("Gender")
  private String gender;

  @JsonProperty("CityOfBirth")
  private String cityOfBirth;

  @JsonProperty("Active")
  private String active;
}
