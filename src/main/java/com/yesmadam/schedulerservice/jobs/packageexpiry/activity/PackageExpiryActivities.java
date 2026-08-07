package com.yesmadam.schedulerservice.jobs.packageexpiry.activity;

import java.util.List;
import java.util.Map;

import com.yesmadam.persistence.MultiSessionReports;
import com.yesmadam.persistence.PackageSession;
import com.yesmadam.persistence.Services;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activity interface for package expiry business logic operations.
 *
 * <p>This interface defines the activity methods that perform the actual database operations
 * required for the package expiry workflow. Activities are the building blocks that contain the
 * business logic and are executed by Temporal workers.
 *
 * <p>Each method represents a specific operation in the package expiry process:
 *
 * <ul>
 *   <li>Fetching active package sessions that need to be checked for expiry
 *   <li>Retrieving service information for cost calculations
 *   <li>Marking packages as expired in the database
 *   <li>Saving financial reports for the expired packages
 * </ul>
 *
 * <p>Activities are automatically retried by Temporal on failure, and each method is decorated with
 * {@code @Loggable} and {@code @HandleExceptions} annotations for observability and error handling.
 *
 * @author Scheduler Service Team
 * @since 1.0.0
 */
@ActivityInterface
public interface PackageExpiryActivities {

  /**
   * Fetches all active package sessions from the database.
   *
   * <p>This method retrieves packages that are currently active and need to be evaluated for
   * potential expiry. Active packages are those with status = {@code ACTIVE}.
   *
   * @return list of active package sessions, may be empty if no active packages exist
   */
  @ActivityMethod
  List<PackageSession> fetchActivePackageSessions();

  /**
   * Fetches service information for the specified service IDs.
   *
   * <p>Retrieves service details including pricing information that is required for generating
   * financial reports when packages expire.
   *
   * @param serviceIds list of service IDs to fetch information for, may be null or empty
   * @return map of service ID to Services entity, empty map if input is null/empty or no services
   *     found
   */
  @ActivityMethod
  Map<Integer, Services> fetchServices(List<Integer> serviceIds);

  /**
   * Marks the specified package sessions as expired in the database.
   *
   * <p>Updates the status of all provided package sessions to {@code EXPIRED} and persists the
   * changes. This is a bulk operation for efficiency.
   *
   * @param packageSessions list of package sessions to mark as expired, may be null or empty
   */
  @ActivityMethod
  void expirePackageSessions(List<PackageSession> packageSessions);

  /**
   * Saves multi-session financial reports to the database.
   *
   * <p>Persists financial tracking reports for expired packages. These reports contain transaction
   * information used for accounting and business analytics.
   *
   * @param multiSessionReports list of multi-session reports to save, may be null or empty
   */
  @ActivityMethod
  void saveMultiSessionReports(List<MultiSessionReports> multiSessionReports);
}
