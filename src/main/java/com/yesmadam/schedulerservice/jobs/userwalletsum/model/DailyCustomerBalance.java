package com.yesmadam.schedulerservice.jobs.userwalletsum.model;

import java.sql.Timestamp;

import javax.persistence.*;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/** One daily snapshot of the wallet sums, written once per workflow run. */
@Data
@Entity
@Table(name = "tbl_daily_customer_balance", catalog = DBConstants.YESMADAM_USERS)
public class DailyCustomerBalance {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  private Long mainWalletSum;
  private Long seemlessWalletSum;
  private Timestamp createdAt;
}
