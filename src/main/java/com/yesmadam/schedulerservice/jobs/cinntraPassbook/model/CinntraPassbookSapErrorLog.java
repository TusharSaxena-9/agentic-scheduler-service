package com.yesmadam.schedulerservice.jobs.cinntraPassbook.model;

import java.sql.Timestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/**
 * Mirrors tracking's {@code tbl_sap_error_log}.
 *
 * <p><b>Deliberate deviation</b>, same as elite-mismatch-sync: tracking stores a curl command that
 * embeds the live Cinntra Key/userId headers in {@code requestJson}. This port stores the plain JSON
 * request body only.
 */
@Data
@Entity
@Table(name = "tbl_sap_error_log", catalog = DBConstants.YESMADAM_ADMIN)
public class CinntraPassbookSapErrorLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @Column(name = "orderId")
  private Long orderId;

  @Column(name = "requestType")
  private Integer requestType;

  @Column(name = "requestJson", columnDefinition = "TEXT")
  private String requestJson;

  @Column(name = "responseJson", columnDefinition = "TEXT")
  private String responseJson;

  @Column(name = "errorMessage", columnDefinition = "TEXT")
  private String errorMessage;

  @Column(name = "createdAt")
  private Timestamp createdAt;
}
