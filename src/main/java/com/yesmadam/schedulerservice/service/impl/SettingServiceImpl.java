package com.yesmadam.schedulerservice.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.persistence.SettingsForEnalbeDisable;
import com.yesmadam.schedulerservice.repository.interfaces.SettingRepository;
import com.yesmadam.schedulerservice.service.interfaces.SettingService;

@Service
public class SettingServiceImpl implements SettingService {
  private final SettingRepository settingRepository;

  public SettingServiceImpl(SettingRepository settingRepository) {
    this.settingRepository = settingRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<String, SettingsForEnalbeDisable> findSettingWithKeys(List<String> configName) {
    return settingRepository.findByKeyInIgnoreCase(configName).stream()
        .collect(Collectors.toMap(SettingsForEnalbeDisable::getKey, Function.identity()));
  }

  @Override
  @Transactional
  public boolean updateSettingValue(String key, String value) {
    Set<SettingsForEnalbeDisable> matches =
        settingRepository.findByKeyInIgnoreCase(List.of(key));
    if (matches.isEmpty()) {
      return false;
    }
    matches.forEach(setting -> setting.setValue(value));
    settingRepository.saveAll(matches);
    return true;
  }
}
