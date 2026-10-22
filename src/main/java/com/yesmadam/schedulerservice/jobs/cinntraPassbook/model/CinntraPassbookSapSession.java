package com.yesmadam.schedulerservice.jobs.cinntraPassbook.model;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/** Minimal projection of {@code tbl_sap_session} — the current SAP B1 session cookie, row id {@code 1}. */
@Data
@Entity
@Table(name = "tbl_sap_session", catalog = DBConstants.YESMADAM_ADMIN)
public class CinntraPassbookSapSession {

  @Id private Integer id;

  private String sessionId;
}
