package com.yesmadam.scheduler_service.packageexpiry.activity;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.yesmadam.scheduler_service.annotation.HandleExceptions;
import com.yesmadam.scheduler_service.annotation.Loggable;
import com.yesmadam.scheduler_service.packageexpiry.model.MultiSessionReports;
import com.yesmadam.scheduler_service.packageexpiry.model.PackageSession;
import com.yesmadam.scheduler_service.packageexpiry.model.Services;
import com.yesmadam.scheduler_service.packageexpiry.service.PackageExpiryService;

import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of package expiry activities for Temporal workflow execution.
 *
 * <p>This class implements the {@link PackageExpiryActivities} interface and provides the actual
 * business logic for each activity method. Each method is decorated with {@code @Loggable} for
 * automatic operation logging and {@code @HandleExceptions} for centralized exception handling.
 *
 * <p>The implementation delegates to {@link PackageExpiryService} for the actual database
 * operations, following a clean architecture pattern where activities act as adapters between
 * Temporal and the business logic layer.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Automatic logging of operation start, completion, and timing
 *   <li>Centralized exception handling with proper error categorization
 *   <li>Delegation to service layer for business logic
 *   <li>Spring component management for dependency injection
 * </ul>
 *
 * @author Scheduler Service Team
 * @since 1.0.0
 */
@Slf4j
@Component
public class PackageExpiryActivitiesImpl implements PackageExpiryActivities {

  private final PackageExpiryService packageExpiryService;

  /**
   * Creates a new PackageExpiryActivitiesImpl with required dependencies.
   *
   * @param packageExpiryService the service that handles the actual business logic
   */
  public PackageExpiryActivitiesImpl(PackageExpiryService packageExpiryService) {
    this.packageExpiryService = packageExpiryService;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Delegates to {@link PackageExpiryService#getActivePackageSessions()}. Logs the operation as
   * "fetch-active-package-sessions".
   */
  @Override
  @Loggable(operation = "fetch-active-package-sessions")
  @HandleExceptions
  public List<PackageSession> fetchActivePackageSessions() {
    return packageExpiryService.getActivePackageSessions();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Delegates to {@link PackageExpiryService#fetchServices(List)}. Logs the operation as
   * "fetch-services".
   */
  @Override
  @Loggable(operation = "fetch-services")
  @HandleExceptions
  public Map<Integer, Services> fetchServices(List<Integer> serviceIds) {
    return packageExpiryService.fetchServices(serviceIds);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Delegates to {@link PackageExpiryService#expirePackageSessions(List)}. Logs the operation as
   * "expire-package-sessions".
   */
  @Override
  @Loggable(operation = "expire-package-sessions")
  @HandleExceptions
  public void expirePackageSessions(List<PackageSession> packageSessions) {
    packageExpiryService.expirePackageSessions(packageSessions);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Delegates to {@link PackageExpiryService#saveMultiSessionReports(List)}. Logs the operation
   * as "save-multi-session-reports".
   */
  @Override
  @Loggable(operation = "save-multi-session-reports")
  @HandleExceptions
  public void saveMultiSessionReports(List<MultiSessionReports> multiSessionReports) {
    packageExpiryService.saveMultiSessionReports(multiSessionReports);
  }
}
