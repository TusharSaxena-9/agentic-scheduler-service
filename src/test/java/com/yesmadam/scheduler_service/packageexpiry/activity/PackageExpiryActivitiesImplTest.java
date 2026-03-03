package com.yesmadam.scheduler_service.packageexpiry.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yesmadam.scheduler_service.packageexpiry.model.MultiSessionReports;
import com.yesmadam.scheduler_service.packageexpiry.model.PackageSession;
import com.yesmadam.scheduler_service.packageexpiry.model.Services;
import com.yesmadam.scheduler_service.packageexpiry.service.PackageExpiryService;

@ExtendWith(MockitoExtension.class)
class PackageExpiryActivitiesImplTest {

  @Mock PackageExpiryService packageExpiryService;
  @InjectMocks PackageExpiryActivitiesImpl activities;

  @Test
  void fetchActivePackageSessions_shouldDelegateToService() {
    PackageSession session = new PackageSession();
    when(packageExpiryService.getActivePackageSessions()).thenReturn(List.of(session));

    List<PackageSession> result = activities.fetchActivePackageSessions();

    assertThat(result).hasSize(1);
    verify(packageExpiryService).getActivePackageSessions();
  }

  @Test
  void fetchActivePackageSessions_shouldReturnEmptyList() {
    when(packageExpiryService.getActivePackageSessions()).thenReturn(Collections.emptyList());

    List<PackageSession> result = activities.fetchActivePackageSessions();

    assertThat(result).isEmpty();
  }

  @Test
  void fetchServices_shouldDelegateToService() {
    Services service = new Services();
    service.setId(1);
    when(packageExpiryService.fetchServices(List.of(1))).thenReturn(Map.of(1, service));

    Map<Integer, Services> result = activities.fetchServices(List.of(1));

    assertThat(result).containsKey(1);
    verify(packageExpiryService).fetchServices(List.of(1));
  }

  @Test
  void expirePackageSessions_shouldDelegateToService() {
    PackageSession session = new PackageSession();

    activities.expirePackageSessions(List.of(session));

    verify(packageExpiryService).expirePackageSessions(List.of(session));
  }

  @Test
  void saveMultiSessionReports_shouldDelegateToService() {
    MultiSessionReports report = new MultiSessionReports();

    activities.saveMultiSessionReports(List.of(report));

    verify(packageExpiryService).saveMultiSessionReports(List.of(report));
  }
}
