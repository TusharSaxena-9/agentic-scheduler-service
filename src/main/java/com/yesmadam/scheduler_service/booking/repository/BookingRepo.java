package com.yesmadam.scheduler_service.booking.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.scheduler_service.booking.model.BookingCount;

/**
 * Repository interface for BookingCount entities. Provides CRUD operations for booking count data
 * access.
 */
@Repository
public interface BookingRepo extends JpaRepository<BookingCount, Long> {}
