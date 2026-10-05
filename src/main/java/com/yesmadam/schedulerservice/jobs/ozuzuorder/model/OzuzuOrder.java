package com.yesmadam.schedulerservice.jobs.ozuzuorder.model;

import java.sql.Timestamp;
import java.util.Set;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.OneToMany;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/**
 * One synced Ozuzu (Shopify, via Unicommerce) sale order, in {@code tbl_ozuzu_order}.
 *
 * <p>Keyed by {@code code} — Unicommerce's own order identifier — which {@code OzuzuOrderWriter}
 * uses to decide insert vs. update: {@code code} is stable across a Temporal retry, unlike a
 * generated {@code id} (rule 14 in CLAUDE.md).
 */
@Data
@Entity
@Table(name = "tbl_ozuzu_order", catalog = DBConstants.YESMADAM_ADMIN)
public class OzuzuOrder {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "code")
  private String code;

  @Column(name = "display_order_code")
  private String displayOrderCode;

  @Column(name = "order_status")
  private String orderStatus;

  @Column(name = "state")
  private String state;

  @Column(name = "name")
  private String name;

  @Column(name = "customer_code")
  private String customerCode;

  @Column(name = "address")
  private String address;

  @Column(name = "pin_code")
  private String pinCode;

  @Column(name = "payment_mode")
  private String paymentMode;

  @Column(name = "transaction_id")
  private String transactionId;

  @Column(name = "source")
  private String source;

  @Column(name = "shipping_courier")
  private String shippingCourier;

  @Column(name = "shipment_id")
  private String shipmentId;

  @Column(name = "tracking_number")
  private String trackingNumber;

  @Column(name = "created")
  private Timestamp created;

  @Column(name = "updated")
  private Timestamp updated;

  @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
  @JoinColumn(name = "order_id")
  private Set<OzuzuOrderProduct> orderProducts;
}
