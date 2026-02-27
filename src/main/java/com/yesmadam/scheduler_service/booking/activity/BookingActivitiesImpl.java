package com.yesmadam.scheduler_service.booking.activity;

import com.yesmadam.scheduler_service.booking.repository.BookingRepo;
import com.yesmadam.scheduler_service.booking.model.BookingCount;
import com.yesmadam.scheduler_service.exception.handler.GlobalExceptionHandler;
import com.yesmadam.scheduler_service.logging.LoggingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
public class BookingActivitiesImpl implements BookingActivities {

    private final BookingRepo bookingRepo;
    private final GlobalExceptionHandler exceptionHandler;

    /**
     * Constructor for BookingActivitiesImpl.
     * @param bookingRepo the repository for booking data access
     * @param exceptionHandler the global exception handler
     */
    public BookingActivitiesImpl(BookingRepo bookingRepo, 
                               @Autowired GlobalExceptionHandler exceptionHandler) {
        this.bookingRepo = bookingRepo;
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * Fetches all booking counts from the database.
     * @return a list of all booking counts
     */
    @Override
    public List<BookingCount> fetchBookingCounts() {
        long startTime = LoggingUtils.logOperationStart("database-fetch-booking-counts");
        
        try {
            List<BookingCount> counts = bookingRepo.findAll();
            LoggingUtils.logOperationComplete("database-fetch-booking-counts", startTime);
            log.info("Successfully fetched {} booking counts from database", counts.size());
            LoggingUtils.logPerformanceMetric("database-fetch-count", counts.size(), "records");
            return counts;
            
        } catch (DataAccessException ex) {
            exceptionHandler.handleDataAccessException(ex);
            throw new RuntimeException("Failed to fetch booking counts from database", ex);
        } catch (RuntimeException ex) {
            exceptionHandler.handleRuntimeException(ex);
            throw ex;
        } catch (Exception ex) {
            exceptionHandler.handleGenericException(ex);
            throw new RuntimeException("Failed to fetch booking counts from database", ex);
        }
    }
    /**
     * Saves a list of booking counts to the database.
     * Sets the local date time for each booking count before saving.
     * @param counts the list of booking counts to save
     */
    @Override
    public void saveBookingCounts(List<BookingCount> counts) {
        if (counts == null || counts.isEmpty()) {
            log.warn("No booking counts to save - empty or null list provided");
            exceptionHandler.handleIllegalArgumentException(
                new IllegalArgumentException("Booking counts list cannot be null or empty")
            );
            return;
        }
        
        long startTime = LoggingUtils.logOperationStart("database-save-booking-counts");
        
        try {
            // Set timestamp for each record
            counts.forEach(c -> {
                if (c != null) {
                    c.setLocalDateTime(LocalDateTime.now());
                }
            });
            
            List<BookingCount> savedCounts = bookingRepo.saveAll(counts);
            LoggingUtils.logOperationComplete("database-save-booking-counts", startTime);
            log.info("Successfully saved {} booking counts to database", savedCounts.size());
            LoggingUtils.logPerformanceMetric("database-save-count", savedCounts.size(), "records");
            
        } catch (DataAccessException ex) {
            exceptionHandler.handleDataAccessException(ex);
            throw new RuntimeException("Failed to save booking counts to database", ex);
        } catch (RuntimeException ex) {
            exceptionHandler.handleRuntimeException(ex);
            throw ex;
        } catch (Exception ex) {
            exceptionHandler.handleGenericException(ex);
            throw new RuntimeException("Failed to save booking counts to database", ex);
        }
    }
}
