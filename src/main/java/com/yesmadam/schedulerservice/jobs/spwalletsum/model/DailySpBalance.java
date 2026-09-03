package com.yesmadam.schedulerservice.jobs.spwalletsum.model;

import java.sql.Timestamp;

import javax.persistence.*;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/** One daily snapshot of the SP wallet sums, written once per workflow run. */
@Data
@Entity
@Table(name = "tbl_daily_sp_balance", catalog = DBConstants.YESMADAM_ADMIN)
public class DailySpBalance {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  private Long mainActiveWalletSum;
  private Long savingActiveWalletSum;
  private Long mainDeActiveWalletSum;
  private Long savingDeActiveWalletSum;
  private Timestamp createdAt;
}
