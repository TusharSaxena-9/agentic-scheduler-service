package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/** Minimal projection of {@code tbl_ym_elite} — the subscription plan's default fee, used as a last-resort fallback amount. */
@Data
@Entity
@Table(name = "tbl_ym_elite", catalog = DBConstants.YESMADAM_ADMIN)
public class EliteMismatchSyncYmElite {

  @Id private Integer id;

  private Integer amount;
}
