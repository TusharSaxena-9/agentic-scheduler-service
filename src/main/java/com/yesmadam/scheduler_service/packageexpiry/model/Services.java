package com.yesmadam.scheduler_service.packageexpiry.model;

import javax.persistence.*;

import com.yesmadam.scheduler_service.common.constants.DBConstants;

import lombok.Data;

@Data
@Entity
@Table(name = "tbl_services", catalog = DBConstants.YESMADAM_ADMIN)
public class Services {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @Column(name = "`name`")
  private String name;

  @Column(name = "pack_charge")
  private Integer packCharge;

  @Column(name = "no_of_sessions")
  private Integer noOfSessions;

  @Column(name = "conv_fee")
  private Integer convFee;

  @Column(name = "is_session")
  private Boolean isSession;

  @Column(name = "is_package")
  private Boolean isPackage;
}
