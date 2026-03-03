package com.yesmadam.scheduler_service.packageexpiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
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
import com.yesmadam.scheduler_service.packageexpiry.model.enums.PackageSessionEnum;
import com.yesmadam.scheduler_service.packageexpiry.repository.MultiSessionReportsRepository;
import com.yesmadam.scheduler_service.packageexpiry.repository.PackageSessionRepository;
import com.yesmadam.scheduler_service.packageexpiry.repository.ServicesRepository;

@ExtendWith(MockitoExtension.class)
class PackageExpiryServiceTest {

  @Mock PackageSessionRepository packageSessionRepository;
  @Mock ServicesRepository servicesRepository;
  @Mock MultiSessionReportsRepository multiSessionReportsRepository;
  @InjectMocks PackageExpiryService packageExpiryService;

  // ================================================================
  // getActivePackageSessions
  // ================================================================

  @Test
  void getActivePackageSessions_shouldReturnActiveSessions() {
    PackageSession session = buildPackageSession(1, PackageSessionEnum.ACTIVE.getStatus());
    when(packageSessionRepository.findByStatus(PackageSessionEnum.ACTIVE.getStatus()))
        .thenReturn(List.of(session));

    List<PackageSession> result = packageExpiryService.getActivePackageSessions();

    assertThat(result).hasSize(1);
    verify(packageSessionRepository).findByStatus(PackageSessionEnum.ACTIVE.getStatus());
  }

  @Test
  void getActivePackageSessions_shouldReturnEmptyIfNoneFound() {
    when(packageSessionRepository.findByStatus(PackageSessionEnum.ACTIVE.getStatus()))
        .thenReturn(Collections.emptyList());

    List<PackageSession> result = packageExpiryService.getActivePackageSessions();

    assertThat(result).isEmpty();
  }

  // ================================================================
  // fetchServices
  // ================================================================

  @Test
  void fetchServices_shouldReturnMapKeyedByServiceId() {
    Services service = buildService(1, 600, 3, 30);
    when(servicesRepository.findByIdIn(List.of(1))).thenReturn(List.of(service));

    Map<Integer, Services> result = packageExpiryService.fetchServices(List.of(1));

    assertThat(result).hasSize(1);
    assertThat(result.get(1)).isEqualTo(service);
  }

  @Test
  void fetchServices_shouldReturnEmptyMapIfServiceIdsEmpty() {
    Map<Integer, Services> result = packageExpiryService.fetchServices(Collections.emptyList());

    assertThat(result).isEmpty();
    verifyNoInteractions(servicesRepository);
  }

  @Test
  void fetchServices_shouldReturnEmptyMapIfServiceIdsNull() {
    Map<Integer, Services> result = packageExpiryService.fetchServices(null);

    assertThat(result).isEmpty();
    verifyNoInteractions(servicesRepository);
  }

  @Test
  void fetchServices_shouldThrowIfDuplicateServiceIdsReturned() {
    Services service = buildService(1, 600, 3, 30);
    List<Services> duplicateResults = new ArrayList<>(List.of(service, service));
    when(servicesRepository.findByIdIn(any())).thenReturn(duplicateResults);

    assertThatThrownBy(() -> packageExpiryService.fetchServices(List.of(1)))
        .isInstanceOf(IllegalStateException.class);
  }

  // ================================================================
  // expirePackageSessions
  // ================================================================

  @Test
  void expirePackageSessions_shouldSetStatusToExpired() {
    PackageSession session = buildPackageSession(1, PackageSessionEnum.ACTIVE.getStatus());

    packageExpiryService.expirePackageSessions(List.of(session));

    assertThat(session.getStatus()).isEqualTo(PackageSessionEnum.EXPIRED.getStatus());
    verify(packageSessionRepository).saveAll(List.of(session));
  }

  @Test
  void expirePackageSessions_shouldExpireAllSessions() {
    PackageSession session1 = buildPackageSession(1, PackageSessionEnum.ACTIVE.getStatus());
    PackageSession session2 = buildPackageSession(2, PackageSessionEnum.ACTIVE.getStatus());

    packageExpiryService.expirePackageSessions(List.of(session1, session2));

    assertThat(session1.getStatus()).isEqualTo(PackageSessionEnum.EXPIRED.getStatus());
    assertThat(session2.getStatus()).isEqualTo(PackageSessionEnum.EXPIRED.getStatus());
    verify(packageSessionRepository).saveAll(any());
  }

  @Test
  void expirePackageSessions_shouldSkipIfEmpty() {
    packageExpiryService.expirePackageSessions(Collections.emptyList());
    verifyNoInteractions(packageSessionRepository);
  }

  @Test
  void expirePackageSessions_shouldSkipIfNull() {
    packageExpiryService.expirePackageSessions(null);
    verifyNoInteractions(packageSessionRepository);
  }

  // ================================================================
  // saveMultiSessionReports
  // ================================================================

  @Test
  void saveMultiSessionReports_shouldSaveAllReports() {
    MultiSessionReports report = new MultiSessionReports();
    report.setCostHead("Pack Expiry");
    report.setAmount(200);

    packageExpiryService.saveMultiSessionReports(List.of(report));

    verify(multiSessionReportsRepository).saveAll(List.of(report));
  }

  @Test
  void saveMultiSessionReports_shouldSkipIfEmpty() {
    packageExpiryService.saveMultiSessionReports(Collections.emptyList());
    verifyNoInteractions(multiSessionReportsRepository);
  }

  @Test
  void saveMultiSessionReports_shouldSkipIfNull() {
    packageExpiryService.saveMultiSessionReports(null);
    verifyNoInteractions(multiSessionReportsRepository);
  }

  // ================================================================
  // Helpers
  // ================================================================

  private PackageSession buildPackageSession(Integer id, Integer status) {
    PackageSession session = new PackageSession();
    session.setId(id);
    session.setStatus(status);
    session.setServiceId(1);
    session.setRemainingSessionCount(3);
    session.setUserId(100L);
    return session;
  }

  private Services buildService(
      Integer id, Integer packCharge, Integer noOfSessions, Integer convFee) {
    Services service = new Services();
    service.setId(id);
    service.setPackCharge(packCharge);
    service.setNoOfSessions(noOfSessions);
    service.setConvFee(convFee);
    return service;
  }
}
