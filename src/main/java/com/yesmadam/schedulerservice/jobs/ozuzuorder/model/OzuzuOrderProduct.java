package com.yesmadam.schedulerservice.jobs.ozuzuorder.model;

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
 * One line item of an {@link OzuzuOrder}, in {@code tbl_ozuzu_order_product}. The owning side of
 * the relationship is {@code OzuzuOrder.orderProducts} (a {@code @JoinColumn(name = "order_id")}),
 * so this entity carries no FK field of its own — the messagingapis original additionally kept an
 * unmanaged {@code order_id} field on this side that nothing ever read, which is not reproduced
 * here.
 *
 * <p>{@code cogs} is mapped for column parity with the original entity even though neither it nor
 * this job's sync logic ever reads or writes it — some other process may populate it out of band.
 */
@Data
@Entity
@Table(name = "tbl_ozuzu_order_product", catalog = DBConstants.YESMADAM_ADMIN)
public class OzuzuOrderProduct {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "order_detail_id")
  private Long orderDetailId;

  @Column(name = "item_sku")
  private String itemSku;

  @Column(name = "item_name")
  private String itemName;

  @Column(name = "max_retail_price")
  private Double maxRetailPrice;

  @Column(name = "discount")
  private Double discount;

  @Column(name = "selling_price")
  private Double sellingPrice;

  @Column(name = "quantity")
  private Integer quantity;

  @Column(name = "hsn_code")
  private String hsnCode;

  @Column(name = "selling_price_without_taxes_and_discount")
  private Double sellingPriceWithoutTaxesAndDiscount;

  @Column(name = "total_integrated_gst")
  private Double totalIntegratedGst;

  @Column(name = "total_central_gst")
  private Double totalCentralGst;

  @Column(name = "total_state_gst")
  private Double totalStateGst;

  @Column(name = "product_status")
  private String productStatus;

  @Column(name = "shipping_package_status")
  private String shippingPackageStatus;

  @Column(name = "cogs")
  private String cogs;

  @Column(name = "facility_name")
  private String facilityName;

  @Column(name = "created")
  private Timestamp created;

  @Column(name = "updated")
  private Timestamp updated;
}
