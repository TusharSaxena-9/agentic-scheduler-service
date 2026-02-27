package com.yesmadam.scheduler_service.booking.model;

import lombok.Data;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * Represents a booking count entity.
 * Stores booking count information with status and timestamp.
 */
@Data
@Entity
@Table(name = "booking_count")
public class BookingCount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long count;

    private String status;

    private LocalDateTime localDateTime;
}
