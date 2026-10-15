package com.yesmadam.schedulerservice.jobs.cinntraLoginAndStoreSession.model;

import java.time.LocalDateTime;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/**
 * Mirrors tracking's {@code tbl_sap_session} — the current SAP B1 Service Layer session cookie.
 * Unlike every other job in this service (which only ever reads this table), this job is the one
 * that writes it: {@code loginAndStoreSession} logs in to Cinntra every run and upserts the single
 * row with id {@code 1}, refreshing {@code sessionId}/{@code insertedAt} for every other job's SAP
 * calls to read.
 */
@Data
@Entity
@Table(name = "tbl_sap_session", catalog = DBConstants.YESMADAM_ADMIN)
public class CinntraSapSession {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  private String sessionId;

  private LocalDateTime insertedAt;
}
