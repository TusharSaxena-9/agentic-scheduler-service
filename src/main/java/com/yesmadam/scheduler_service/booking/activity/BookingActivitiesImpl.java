package com.yesmadam.scheduler_service.booking.activity;

import com.yesmadam.scheduler_service.booking.repository.BookingRepo;
import com.yesmadam.scheduler_service.booking.model.BookingCount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
public class BookingActivitiesImpl implements BookingActivities {

    private final BookingRepo bookingRepo;

    public BookingActivitiesImpl(BookingRepo bookingRepo) {
        this.bookingRepo = bookingRepo;
    }

    @Override
    public List<BookingCount> fetchBookingCounts() {
        List<BookingCount> counts = bookingRepo.findAll();
        log.info("Fetched {} booking counts", counts.size());
        return counts;
    }
    @Override
    public void saveBookingCounts(List<BookingCount> counts) {
        counts.forEach(c -> c.setLocalDateTime(LocalDateTime.now()));
        bookingRepo.saveAll(counts);
        log.info("Saved {} booking counts", counts.size());
    }
}
