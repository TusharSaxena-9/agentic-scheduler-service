package com.yesmadam.scheduler_service.packageexpiry.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.scheduler_service.packageexpiry.model.PackageSession;

/**
 * Spring Data JPA repository for {@link PackageSession} entities.
 *
 * <p>This repository provides database access operations for package sessions in the {@code
 * tbl_packages} table. It extends {@link JpaRepository} to provide standard CRUD operations and
 * includes custom query methods for package expiry workflow requirements.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Standard CRUD operations inherited from JpaRepository
 *   <li>Custom query methods for package status filtering
 *   <li>Optimized queries for package expiry processing
 *   <li>Spring Data transaction management
 * </ul>
 *
 * <p>The repository is primarily used by {@link
 * com.yesmadam.scheduler_service.packageexpiry.service.PackageExpiryService} to:
 *
 * <ul>
 *   <li>Find active packages that need expiry processing
 *   <li>Update package statuses to expired
 *   <li>Perform bulk operations for efficiency
 * </ul>
 *
 * @author Scheduler Service Team
 * @since 1.0.0
 */
@Repository
public interface PackageSessionRepository extends JpaRepository<PackageSession, Integer> {

  /**
   * Finds all package sessions with the specified status.
   *
   * <p>This method is used by the package expiry workflow to retrieve all active packages that need
   * to be processed for potential expiry. The query is optimized for performance in batch
   * processing scenarios.
   *
   * @param status the status value to filter by (corresponds to {@link
   *     com.yesmadam.scheduler_service.packageexpiry.service.PackageExpiryService})
   * @return list of package sessions with the specified status, empty list if none found
   */
  List<PackageSession> findByStatus(Integer status);
}
