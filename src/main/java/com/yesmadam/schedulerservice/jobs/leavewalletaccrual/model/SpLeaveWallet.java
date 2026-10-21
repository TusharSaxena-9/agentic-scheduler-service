package com.yesmadam.schedulerservice.jobs.leavewalletaccrual.model;

import java.sql.Timestamp;

import javax.persistence.*;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

@Data
@Entity
@Table(name = "tbl_sp_leave_wallet", catalog = DBConstants.YESMADAM_ADMIN)
public class SpLeaveWallet {

  @Id private Integer spid;

  @Column(name = "`leave`")
  private Double leave;

  private Timestamp createdAt;
  private Timestamp updatedAt;
}
