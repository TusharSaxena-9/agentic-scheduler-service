package com.yesmadam.schedulerservice.jobs.sebaltagging.model;

import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QualifyingFirstOrderRow {

  private Long orderId;
  private Long userId;
  private Integer spId;
  private LocalDate bookingDate;
  private Integer subCategoryId;
  private boolean rebooked;
}
