package com.yesmadam.schedulerservice.jobs.ozuzuorder.service;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.OzuzuOrder;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.OzuzuOrderProduct;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce.UnicommerceAddress;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce.UnicommerceOrderDetail;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce.UnicommerceSaleOrderItem;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce.UnicommerceShippingPackage;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.repository.OzuzuOrderRepository;

/**
 * Every read and write the Ozuzu sync performs, on its own bean per rule 13.
 *
 * <p><b>Two lookups, deliberately writer-routed, not readOnly.</b> {@link #existingCodes} backs
 * the create phase's skip-if-present check; {@link #existingOrdersByCode} backs the update phase's
 * skip-if-absent check. Both must see rows the create phase just committed in the same run — a
 * replica-routed read here could observe a just-inserted code as still absent and either
 * double-create it or wrongly skip its update, the same read-after-write hazard {@code
 * AutoJobDistributionReader} documents for its own writer-routed reads. Do not add {@code
 * readOnly = true} to either.
 *
 * <p><b>Mirrors the messagingapis original's two-phase shape exactly</b> rather than collapsing
 * create/update into one upsert: {@link #insertNew} only ever runs for a code the caller has
 * already confirmed is absent, {@link #updateExisting} only for one already loaded present. The
 * caller (service) decides which branch a code takes from one batch lookup, precisely as {@code
 * OzuzuServiceImpl.processCreateOrders}/{@code processUpdateOrders} decided from one {@code
 * existingOrdersMap} each.
 *
 * <p><b>Idempotent under retry (rule 14) despite the skip logic.</b> {@link #existingCodes} /
 * {@link #existingOrdersByCode} are re-queried fresh on every activity attempt, never cached
 * across a retry — so a retried create activity sees its own prior (committed) inserts as already
 * present and correctly skips them instead of duplicating, and a retried update activity re-applies
 * the same field values it already applied.
 */
@Service
public class OzuzuOrderWriter {

  private final OzuzuOrderRepository ozuzuOrderRepository;

  public OzuzuOrderWriter(OzuzuOrderRepository ozuzuOrderRepository) {
    this.ozuzuOrderRepository = ozuzuOrderRepository;
  }

  /** Codes, out of {@code orderCodes}, that already have a row — the create phase skips these. */
  @Transactional
  public Set<String> existingCodes(List<String> orderCodes) {
    Set<String> codes = new HashSet<>();
    ozuzuOrderRepository.findByCodeIn(orderCodes).forEach(order -> codes.add(order.getCode()));
    return codes;
  }

  /** Rows, out of {@code orderCodes}, that already exist — the update phase skips everything else. */
  @Transactional
  public Map<String, OzuzuOrder> existingOrdersByCode(List<String> orderCodes) {
    return ozuzuOrderRepository.findByCodeIn(orderCodes).stream()
        .collect(Collectors.toMap(OzuzuOrder::getCode, Function.identity(), (a, b) -> b, HashMap::new));
  }

  /**
   * Inserts a brand-new order. Caller must already have confirmed {@code detail.getCode()} is not
   * in {@link #existingCodes} — this method does not check.
   */
  @Transactional
  public void insertNew(UnicommerceOrderDetail detail) {
    OzuzuOrder order = new OzuzuOrder();
    order.setCode(detail.getCode());
    order.setDisplayOrderCode(detail.getDisplayOrderCode());
    order.setCreated(toTimestamp(detail.getCreated()));

    applyStatusFields(order, detail);

    UnicommerceAddress billing = detail.getBillingAddress();
    order.setName(billing != null ? billing.getName() : null);
    order.setAddress(buildAddress(billing));
    order.setState(billing != null ? billing.getState() : null);
    order.setPinCode(billing != null ? billing.getPincode() : null);

    Map<String, Object> paymentDetail = detail.getPaymentDetail();
    order.setPaymentMode(paymentDetail != null ? asString(paymentDetail.get("paymentMode")) : null);
    order.setTransactionId(
        paymentDetail != null ? asString(paymentDetail.get("transactionId")) : null);

    order.setSource(detail.getSource());
    order.setCustomerCode(detail.getCustomerCode());
    order.setOrderProducts(buildOrderProducts(detail));

    ozuzuOrderRepository.save(order);
  }

  /**
   * Refreshes an existing order's status/tracking fields and per-item product status. Caller must
   * already have loaded {@code order} via {@link #existingOrdersByCode} in the same activity
   * execution.
   */
  @Transactional
  public void updateExisting(OzuzuOrder order, UnicommerceOrderDetail detail) {
    applyStatusFields(order, detail);
    order.setUpdated(toTimestamp(detail.getUpdated()));
    refreshProductStatuses(order, detail);
    ozuzuOrderRepository.save(order);
  }

  private void applyStatusFields(OzuzuOrder order, UnicommerceOrderDetail detail) {
    UnicommerceShippingPackage shippingPackage =
        detail.getShippingPackages() == null || detail.getShippingPackages().isEmpty()
            ? null
            : detail.getShippingPackages().get(0);

    String orderStatus =
        shippingPackage == null || "CANCELLED".equals(detail.getStatus())
            ? detail.getStatus()
            : shippingPackage.getStatus();

    order.setOrderStatus(orderStatus);
    order.setShippingCourier(shippingPackage != null ? shippingPackage.getShippingCourier() : null);
    order.setShipmentId(shippingPackage != null ? shippingPackage.getCode() : null);
    order.setTrackingNumber(shippingPackage != null ? shippingPackage.getTrackingNumber() : null);
  }

  private void refreshProductStatuses(OzuzuOrder order, UnicommerceOrderDetail detail) {
    if (order.getOrderProducts() == null || detail.getSaleOrderItems() == null) {
      return;
    }

    Map<Long, UnicommerceSaleOrderItem> itemsByDetailId =
        detail.getSaleOrderItems().stream()
            .filter(item -> item.getId() != null)
            .collect(
                Collectors.toMap(UnicommerceSaleOrderItem::getId, Function.identity(), (a, b) -> b));

    Timestamp updated = toTimestamp(detail.getUpdated());

    order
        .getOrderProducts()
        .forEach(
            product -> {
              UnicommerceSaleOrderItem item = itemsByDetailId.get(product.getOrderDetailId());
              if (item == null) {
                return;
              }
              product.setProductStatus(item.getStatusCode());
              product.setShippingPackageStatus(item.getShippingPackageStatus());
              product.setUpdated(updated);
            });
  }

  private Set<OzuzuOrderProduct> buildOrderProducts(UnicommerceOrderDetail detail) {
    Set<OzuzuOrderProduct> products = new HashSet<>();
    if (detail.getSaleOrderItems() == null) {
      return products;
    }

    Timestamp created = toTimestamp(detail.getCreated());
    for (UnicommerceSaleOrderItem item : detail.getSaleOrderItems()) {
      OzuzuOrderProduct product = new OzuzuOrderProduct();
      product.setOrderDetailId(item.getId());
      product.setItemSku(item.getItemSku());
      product.setItemName(item.getItemName());
      product.setMaxRetailPrice(item.getMaxRetailPrice());
      product.setDiscount(item.getDiscount());
      product.setSellingPrice(item.getSellingPrice());
      product.setQuantity(1);
      product.setHsnCode(item.getHsnCode());
      product.setSellingPriceWithoutTaxesAndDiscount(item.getSellingPriceWithoutTaxesAndDiscount());
      product.setTotalIntegratedGst(item.getTotalIntegratedGst());
      product.setTotalCentralGst(item.getTotalCentralGst());
      product.setTotalStateGst(item.getTotalStateGst());
      product.setProductStatus(item.getStatusCode());
      product.setShippingPackageStatus(item.getShippingPackageStatus());
      product.setFacilityName(item.getFacilityName());
      product.setCreated(created);
      products.add(product);
    }
    return products;
  }

  private String buildAddress(UnicommerceAddress address) {
    if (address == null) {
      return null;
    }
    return address.getAddressLine1()
        + ","
        + (address.getCity() != null ? address.getCity() : "")
        + ","
        + address.getState()
        + ","
        + address.getCountry();
  }

  private String asString(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private Timestamp toTimestamp(Long epochMillis) {
    return epochMillis == null ? null : new Timestamp(epochMillis);
  }
}
