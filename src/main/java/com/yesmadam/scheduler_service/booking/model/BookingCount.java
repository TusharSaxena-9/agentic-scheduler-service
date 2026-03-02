package com.yesmadam.scheduler_service.booking.model;

import java.time.LocalDateTime;

import javax.persistence.*;

import lombok.Data;

/**
 * Represents a booking count entity. Stores booking count information with status and timestamp.
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
