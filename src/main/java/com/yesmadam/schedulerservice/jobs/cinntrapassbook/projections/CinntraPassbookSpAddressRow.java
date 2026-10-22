package com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An SP's registered address, from {@code tbl_sp_address} — for the address-retry PATCH.
 *
 * <p>Read-only DTO filled by an HQL constructor expression (CLAUDE.md rule 16), so the field order
 * is the constructor order the query relies on. Never crosses the activity boundary.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CinntraPassbookSpAddressRow {

  private Integer id;

  private Integer spId;

  private String address1;

  private String city;

  private Integer pincode;
}
