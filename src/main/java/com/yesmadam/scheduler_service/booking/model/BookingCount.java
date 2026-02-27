package com.yesmadam.scheduler_service.booking.model;

import lombok.Data;

import javax.persistence.*;
import java.time.LocalDateTime;

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
