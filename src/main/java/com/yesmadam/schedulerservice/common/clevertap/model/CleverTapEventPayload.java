package com.yesmadam.schedulerservice.common.clevertap.model;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class CleverTapEventPayload {

  private Integer identity;

  private String evtName;

  private Map<String, Object> evtData;
}
