package com.yesmadam.scheduler_service.booking.repository;

import com.yesmadam.scheduler_service.booking.model.BookingCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for BookingCount entities.
 * Provides CRUD operations for booking count data access.
 */
@Repository
public interface BookingRepo extends JpaRepository<BookingCount,Long> {
}
