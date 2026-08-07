package com.yesmadam.schedulerservice.jobs.sebaltagging.model;

import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class DateRangeChunk {

  private LocalDate start;
  private LocalDate end;
}
