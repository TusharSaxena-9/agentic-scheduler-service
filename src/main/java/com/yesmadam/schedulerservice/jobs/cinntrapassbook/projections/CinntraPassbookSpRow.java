package com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An SP's name and hub, from {@code tbl_sp}.
 *
 * <p>Read-only DTO filled by an HQL constructor expression (CLAUDE.md rule 16), so the field order
 * is the constructor order the query relies on. Never crosses the activity boundary.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CinntraPassbookSpRow {

  private Integer id;

  private String name;

  private Integer hubId;
}
