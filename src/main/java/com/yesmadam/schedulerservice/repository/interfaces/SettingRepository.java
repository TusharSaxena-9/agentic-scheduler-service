package com.yesmadam.schedulerservice.repository.interfaces;

import java.util.Collection;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.SettingsForEnalbeDisable;

@Repository
public interface SettingRepository extends JpaRepository<SettingsForEnalbeDisable, Integer> {

  Set<SettingsForEnalbeDisable> findByKeyInIgnoreCase(Collection<String> keys);
}
