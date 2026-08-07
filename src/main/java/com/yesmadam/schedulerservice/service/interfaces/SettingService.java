package com.yesmadam.schedulerservice.service.interfaces;

import java.util.List;
import java.util.Map;

import com.yesmadam.persistence.SettingsForEnalbeDisable;

public interface SettingService {
  Map<String, SettingsForEnalbeDisable> findSettingWithKeys(List<String> configName);

  /**
   * Overwrites an existing {@code tbl_settings} row's value.
   *
   * <p>Update only — a missing key is left alone rather than created, since a row here also carries
   * {@code page} and {@code value1} that this service has no basis to invent.
   *
   * @return {@code true} if a row was found and updated
   */
  boolean updateSettingValue(String key, String value);
}
