package com.yesmadam.schedulerservice.jobs.packageexpiry.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;

import com.yesmadam.persistence.MultiSessionReports;
import com.yesmadam.persistence.PackageSession;
import com.yesmadam.persistence.Services;
import com.yesmadam.enumu.PackageSessionEnum;
import com.yesmadam.schedulerservice.jobs.packageexpiry.repository.MultiSessionReportsRepository;
import com.yesmadam.schedulerservice.jobs.packageexpiry.repository.PackageSessionRepository;
import com.yesmadam.schedulerservice.jobs.packageexpiry.repository.ServicesRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Service class that handles business logic for package expiry operations.
 *
 * <p>This service provides methods to:
 *
 * <ul>
 *   <li>Fetch active package sessions from the database
 *   <li>Retrieve service information for cost calculations
 *   <li>Mark package sessions as expired
 *   <li>Save multi-session reports for financial tracking
 * </ul>
 *
 * <p>The service is used by the PackageExpiryActivities to perform the actual database operations
 * required for the package expiry workflow.
 *
 * @author Scheduler Service Team
 * @since 1.0.0
 */
@Slf4j
@Service
public class PackageExpiryService {

  private final PackageSessionRepository packageSessionRepository;
  private final ServicesRepository servicesRepository;
  private final MultiSessionReportsRepository multiSessionReportsRepository;

  /**
   * Creates a new PackageExpiryService with required dependencies.
   *
   * @param packageSessionRepository repository for package session operations
   * @param servicesRepository repository for service information operations
   * @param multiSessionReportsRepository repository for multi-session report operations
   */
  public PackageExpiryService(
      PackageSessionRepository packageSessionRepository,
      ServicesRepository servicesRepository,
      MultiSessionReportsRepository multiSessionReportsRepository) {
    this.packageSessionRepository = packageSessionRepository;
    this.servicesRepository = servicesRepository;
    this.multiSessionReportsRepository = multiSessionReportsRepository;
  }

  /**
   * Retrieves all active package sessions from the database.
   *
   * <p>Active sessions are those with status = {@link PackageSessionEnum#ACTIVE}. These are the
   * packages that need to be checked for expiry.
   *
   * @return list of active package sessions, empty list if none found
   */
  @Transactional(readOnly = true)
  public List<PackageSession> getActivePackageSessions() {
    return packageSessionRepository.findByStatus(PackageSessionEnum.ACTIVE.getStatus());
  }

  /**
   * Fetches service information for the given service IDs.
   *
   * <p>Returns a map keyed by service ID for efficient lookup during cost calculations in the
   * expiry workflow.
   *
   * @param serviceIds list of service IDs to fetch, can be null or empty
   * @return map of service ID to Services entity, empty map if input is null/empty
   */
  @Transactional(readOnly = true)
  public Map<Integer, Services> fetchServices(List<Integer> serviceIds) {
    if (ObjectUtils.isEmpty(serviceIds)) {
      return Map.of();
    }
    return servicesRepository.findByIdIn(serviceIds).stream()
        .collect(Collectors.toMap(Services::getId, Function.identity()));
  }

  /**
   * Marks the given package sessions as expired and saves them to the database.
   *
   * <p>Sets the status of each package session to {@link PackageSessionEnum#EXPIRED} and performs a
   * bulk update operation.
   *
   * @param packageSessions list of package sessions to expire, can be null or empty
   */
  @Transactional
  public void expirePackageSessions(List<PackageSession> packageSessions) {
    if (ObjectUtils.isEmpty(packageSessions)) return;
    packageSessions.forEach(ps -> ps.setStatus(PackageSessionEnum.EXPIRED.getStatus()));
    //    TODO check how many rows are updated usually and if it's more than 1000 then use batch
    // update
    packageSessionRepository.saveAll(packageSessions);
  }

  /**
   * Saves multi-session reports to the database.
   *
   * <p>These reports are generated for expired packages and contain financial information for
   * tracking and reporting purposes.
   *
   * @param reports list of multi-session reports to save, can be null or empty
   */
  @Transactional
  public void saveMultiSessionReports(List<MultiSessionReports> reports) {
    if (ObjectUtils.isEmpty(reports)) return;
    multiSessionReportsRepository.saveAll(reports);
  }
}
