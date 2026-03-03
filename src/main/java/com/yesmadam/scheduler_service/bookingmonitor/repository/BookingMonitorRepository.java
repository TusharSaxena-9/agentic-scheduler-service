package com.yesmadam.scheduler_service.bookingmonitor.repository;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.scheduler_service.bookingmonitor.model.Booking;
import com.yesmadam.scheduler_service.bookingmonitor.model.BookingPlatformCount;

/**
 * Repository for querying booking counts grouped by platform. NOTE: Update the native query below
 * to match your actual bookings table name and column names before running.
 */
@Repository
public interface BookingMonitorRepository extends JpaRepository<Booking, Long> {

  /**
   * Finds booking counts grouped by platform for the given time window. Returns one row per
   * platform with the platform name and count.
   *
   * @param start start of the time window (inclusive)
   * @param end end of the time window (inclusive)
   * @return list of platform count projections
   *     <p>TODO: Replace tbl_orders, platform_source and created_at with your actual table and
   *     column names.
   */
  @Query(
      """
            SELECT b.orderFrom AS platform, COUNT(b) AS count
            FROM Booking b
            WHERE b.createdAt BETWEEN :start AND :end
            GROUP BY b.orderFrom
            """)
  List<BookingPlatformCount> findBookingCountByPlatform(
      @Param("start") Timestamp start, @Param("end") Timestamp end);
}
