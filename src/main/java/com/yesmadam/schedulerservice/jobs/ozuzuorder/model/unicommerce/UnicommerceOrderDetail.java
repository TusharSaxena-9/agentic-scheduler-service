package com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * The fields {@code OzuzuOrderWriter} actually maps out of Unicommerce's {@code saleorder/get}
 * response. The live payload carries many more fields than this; {@code ignoreUnknown = true}
 * lets Jackson drop them instead of failing the parse.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UnicommerceOrderDetail {

  private String code;
  private String displayOrderCode;
  private String status;
  private String source;
  private String customerCode;
  private Long created;
  private Long updated;

  private UnicommerceAddress billingAddress;
  private List<UnicommerceShippingPackage> shippingPackages;
  private List<UnicommerceSaleOrderItem> saleOrderItems;

  /** Raw JSON object — only {@code paymentMode} and {@code transactionId} are ever read from it. */
  private Map<String, Object> paymentDetail;
}
