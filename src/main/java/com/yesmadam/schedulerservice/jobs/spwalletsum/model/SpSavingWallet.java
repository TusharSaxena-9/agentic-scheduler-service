package com.yesmadam.schedulerservice.jobs.spwalletsum.model;

import javax.persistence.*;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/**
 * Minimal mapping of {@code tbl_sp_saving_wallet}.
 *
 * <p>Unlike {@code tbl_sp_wallet} (mapped as {@link com.yesmadam.persistence.Spwallet} in the
 * shared {@code ym-common-entity} library), no entity for this table exists anywhere in the
 * codebase — messagingapis only ever queried it via a raw Hibernate {@code Session}. This one is
 * local to the job and carries only the columns the sum query needs, so HQL can express the query
 * instead of dropping to native SQL (CLAUDE.md rule 16).
 */
@Data
@Entity
@Table(name = "tbl_sp_saving_wallet", catalog = DBConstants.YESMADAM_ADMIN)
public class SpSavingWallet {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  private Integer spId;
  private Integer savingWalletAmount;
}
