package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/** Mirrors tracking's {@code tbl_sap_mapping} — the customer's SAP card-code mapping, type code 110 ("USER"). */
@Data
@Entity
@Table(name = "tbl_sap_mapping", catalog = DBConstants.YESMADAM_ADMIN)
public class EliteMismatchSyncSapMapping {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  private String type;

  private Integer spId;

  private String sapId;

  private Integer typeCode;
}
