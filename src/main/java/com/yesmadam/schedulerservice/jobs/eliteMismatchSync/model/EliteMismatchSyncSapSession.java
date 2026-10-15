package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/** Minimal projection of {@code tbl_sap_session} — the current SAP B1 session cookie. There is always at most one row, with id {@code 1}. */
@Data
@Entity
@Table(name = "tbl_sap_session", catalog = DBConstants.YESMADAM_ADMIN)
public class EliteMismatchSyncSapSession {

  @Id private Integer id;

  private String sessionId;
}
