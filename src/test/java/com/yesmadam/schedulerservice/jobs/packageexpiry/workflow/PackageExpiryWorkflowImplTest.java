package com.yesmadam.schedulerservice.jobs.packageexpiry.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.yesmadam.schedulerservice.jobs.crontiming.activity.CronTimingActivities;
import com.yesmadam.schedulerservice.jobs.packageexpiry.activity.PackageExpiryActivities;
import com.yesmadam.persistence.MultiSessionReports;
import com.yesmadam.persistence.PackageSession;
import com.yesmadam.persistence.Services;

import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;

class PackageExpiryWorkflowImplTest {

  @RegisterExtension
  static final TestWorkflowExtension testWorkflow =
      TestWorkflowExtension.newBuilder()
          .setWorkflowTypes(PackageExpiryWorkflowImpl.class)
          .setDoNotStart(true)
          .build();

  // ================================================================
  // Stub implementations — Temporal requires real classes not Mockito mocks
  // ================================================================

  static class StubPackageExpiryActivities implements PackageExpiryActivities {
    List<PackageSession> sessionsToReturn = Collections.emptyList();
    Map<Integer, Services> servicesToReturn = Collections.emptyMap();
    List<PackageSession> expiredSessions = new ArrayList<>();
    List<MultiSessionReports> savedReports = new ArrayList<>();
    boolean throwOnExpire = false;

    @Override
    public List<PackageSession> fetchActivePackageSessions() {
      return sessionsToReturn;
    }

    @Override
    public Map<Integer, Services> fetchServices(List<Integer> serviceIds) {
      return servicesToReturn;
    }

    @Override
    public void expirePackageSessions(List<PackageSession> packageSessions) {
      if (throwOnExpire) {
        // Non-retryable so Temporal stops retrying immediately in tests
        throw ApplicationFailure.newNonRetryableFailure("DB failure", "TestFailure");
      }
      expiredSessions.addAll(packageSessions);
    }

    @Override
    public void saveMultiSessionReports(List<MultiSessionReports> multiSessionReports) {
      savedReports.addAll(multiSessionReports);
    }
  }

  static class StubCronTimingActivities implements CronTimingActivities {
    Long idToReturn = 1L;
    Long savedEndId = null;

    @Override
    public Long saveCronStart(String javaFileName, String methodName, String remark) {
      return idToReturn;
    }

    @Override
    public void saveCronEnd(Long cronTimingId) {
      savedEndId = cronTimingId;
    }

    @Override
    public Long saveCronStartWithCounts(
        String javaFileName,
        String methodName,
        String remark,
        Timestamp windowStart,
        Timestamp windowEnd,
        Integer androidCount,
        Integer iosCount) {
      return idToReturn;
    }
  }

  // ================================================================
  // Helpers
  // ================================================================

  private PackageSession buildSession(Integer id, Integer serviceId) {
    PackageSession session = new PackageSession();
    session.setId(id);
    session.setServiceId(serviceId);
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

  private PackageExpiryWorkflow buildWorkflow(
      TestWorkflowEnvironment env,
      Worker worker,
      StubPackageExpiryActivities activities,
      StubCronTimingActivities cronActivities) {
    worker.registerActivitiesImplementations(activities, cronActivities);
    env.start();
    return env.getWorkflowClient()
        .newWorkflowStub(
            PackageExpiryWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(worker.getTaskQueue()).build());
  }

  // ================================================================
  // Happy path
  // ================================================================

  @Test
  void run_shouldExpireSessionsAndSaveReports(TestWorkflowEnvironment env, Worker worker) {
    StubPackageExpiryActivities activities = new StubPackageExpiryActivities();
    StubCronTimingActivities cronActivities = new StubCronTimingActivities();

    activities.sessionsToReturn = List.of(buildSession(1, 1));
    activities.servicesToReturn = Map.of(1, buildService(1, 600, 3, 30));

    buildWorkflow(env, worker, activities, cronActivities).run();

    assertThat(activities.expiredSessions).hasSize(1);
    assertThat(activities.savedReports).hasSize(1);
    assertThat(cronActivities.savedEndId).isEqualTo(1L);
  }

  // ================================================================
  // No active sessions
  // ================================================================

  @Test
  void run_shouldSkipIfNoActiveSessions(TestWorkflowEnvironment env, Worker worker) {
    StubPackageExpiryActivities activities = new StubPackageExpiryActivities();
    StubCronTimingActivities cronActivities = new StubCronTimingActivities();

    activities.sessionsToReturn = Collections.emptyList();

    buildWorkflow(env, worker, activities, cronActivities).run();

    assertThat(activities.expiredSessions).isEmpty();
    assertThat(activities.savedReports).isEmpty();
    assertThat(cronActivities.savedEndId).isEqualTo(1L);
  }

  // ================================================================
  // saveCronEnd always runs even on failure
  // ================================================================

  @Test
  void run_shouldAlwaysSaveCronEndEvenIfExpireFails(TestWorkflowEnvironment env, Worker worker) {
    StubPackageExpiryActivities activities = new StubPackageExpiryActivities();
    StubCronTimingActivities cronActivities = new StubCronTimingActivities();

    activities.sessionsToReturn = List.of(buildSession(1, 1));
    activities.servicesToReturn = Map.of(1, buildService(1, 600, 3, 30));
    activities.throwOnExpire = true;

    try {
      buildWorkflow(env, worker, activities, cronActivities).run();
    } catch (Exception ignored) {
    }

    // Most critical assertion — saveCronEnd must always run even if workflow fails
    assertThat(cronActivities.savedEndId).isEqualTo(1L);
  }

  // ================================================================
  // No services found — reports should be empty
  // ================================================================

  @Test
  void run_shouldNotSaveReportsIfNoServicesFound(TestWorkflowEnvironment env, Worker worker) {
    StubPackageExpiryActivities activities = new StubPackageExpiryActivities();
    StubCronTimingActivities cronActivities = new StubCronTimingActivities();

    activities.sessionsToReturn = List.of(buildSession(1, 1));
    activities.servicesToReturn = Collections.emptyMap();

    buildWorkflow(env, worker, activities, cronActivities).run();

    assertThat(activities.expiredSessions).hasSize(1);
    assertThat(activities.savedReports).isEmpty();
    assertThat(cronActivities.savedEndId).isEqualTo(1L);
  }

  // ================================================================
  // Multiple sessions
  // ================================================================

  @Test
  void run_shouldHandleMultipleSessions(TestWorkflowEnvironment env, Worker worker) {
    StubPackageExpiryActivities activities = new StubPackageExpiryActivities();
    StubCronTimingActivities cronActivities = new StubCronTimingActivities();

    activities.sessionsToReturn = List.of(buildSession(1, 1), buildSession(2, 2));
    activities.servicesToReturn =
        Map.of(
            1, buildService(1, 600, 3, 30),
            2, buildService(2, 900, 3, 60));

    buildWorkflow(env, worker, activities, cronActivities).run();

    assertThat(activities.expiredSessions).hasSize(2);
    assertThat(activities.savedReports).hasSize(2);
    assertThat(cronActivities.savedEndId).isEqualTo(1L);
  }
}
