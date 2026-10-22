package com.yesmadam.schedulerservice.jobs.cinntrapassbook.service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookDocumentLine;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookInvoiceRequest;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookLookups;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookPaymentRequest;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSapResult;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSyncKind;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookEntryRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookHubRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookSapMappingRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookSapSeriesRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookSpRow;

import lombok.extern.slf4j.Slf4j;

/**
 * One passbook entry through tracking's daily postings — {@code syncCommissionPassbookEntry},
 * {@code syncConveniencePassbookEntry}, {@code syncWalletPassbookEntry} and {@code
 * syncBookingOnlinePassbookEntry} — with every lookup served from the chunk's preloaded {@link
 * CinntraPassbookLookups}.
 *
 * <p>Eligibility checks run in tracking's order, so an entry is skipped or failed for the same
 * reason it would be there. Where tracking dereferences a missing row and throws — an SP or order
 * that does not exist, or a hub whose {@code is_franchise} is {@code null} on the commission,
 * convenience and wallet paths, which unbox it — this throws too, and the caller counts a failure.
 * The {@code is_franchise} case looks unintended, but it decides which SPs reach SAP, so it is
 * kept.
 *
 * <p>Returns {@code null} when the entry is not eligible and nothing was sent.
 */
@Slf4j
@Component
public class CinntraPassbookDocumentPoster {

  private static final DateTimeFormatter DOC_DATE_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd");

  /** Notion SPs that still get a straight invoice — tracking's {@code excludeIds}. */
  private static final List<Integer> NOTION_INVOICE_SP_IDS =
      List.of(13002, 12996, 12999, 13687, 4637, 13000);

  private final CinntraPassbookSapClient sapClient;

  public CinntraPassbookDocumentPoster(CinntraPassbookSapClient sapClient) {
    this.sapClient = sapClient;
  }

  /** Posts {@code sp} as {@code kind}'s document. {@code null} when not eligible. */
  public CinntraPassbookSapResult post(
      CinntraPassbookSyncKind kind, CinntraPassbookEntryRow sp, CinntraPassbookLookups lookups) {
    switch (kind) {
      case COMMISSION:
        return postCommission(sp, lookups);
      case CONVENIENCE:
        return postConvenience(sp, lookups);
      case WALLET_RECHARGE:
        return postWalletRecharge(sp, lookups);
      case BOOKING_ONLINE:
        return postBookingOnline(sp, lookups);
      default:
        throw new IllegalArgumentException("Not a document kind: " + kind);
    }
  }

  /**
   * {@code name} contains "laser" — the set tracking's {@code loadLaserSpIds} scans {@code tbl_sp}
   * for.
   */
  static boolean isLaserSp(CinntraPassbookSpRow sp) {
    return nameContains(sp, "laser");
  }

  /**
   * {@code name} contains "notion", minus the SPs invoiced directly — tracking's {@code
   * loadNotionSpIds}.
   */
  static boolean isNotionSp(CinntraPassbookSpRow sp) {
    return nameContains(sp, "notion") && !NOTION_INVOICE_SP_IDS.contains(sp.getId());
  }

  private static boolean nameContains(CinntraPassbookSpRow sp, String keyword) {
    return sp.getName() != null && sp.getName().toLowerCase().contains(keyword);
  }

  /** Reason 11: commission AR invoice (requestType 57), as a draft for Notion SPs. */
  private CinntraPassbookSapResult postCommission(
      CinntraPassbookEntryRow sp, CinntraPassbookLookups lookups) {
    Long orderId = sp.getOrderId() != null ? sp.getOrderId() : 0L;
    CinntraPassbookSpRow spDetail = requireSp(sp, lookups);
    CinntraPassbookHubRow hub = lookups.hub(spDetail.getHubId());
    String costingCode = requireOrderCostingCode(sp, lookups);

    if (lookups.isExcludedCategorySp(spDetail.getId())) {
      log.info(
          "Skipping order id {} because SP {} is working on an excluded category.",
          orderId,
          spDetail.getId());
      return null;
    }
    if (isFranchiseOrLaserSp(hub, spDetail) || isExcludedCommissionSpName(spDetail)) {
      return null;
    }

    CinntraPassbookSapMappingRow sapMap =
        lookups.sapMapping(spDetail.getId(), CinntraPassbookReader.SAP_MAPPING_TYPE_SP);
    CinntraPassbookSapSeriesRow seriesCode = lookups.series(hub.getSapWhsCode());
    if (seriesCode == null) {
      log.warn("Skipping record, seriesCode not found for spId={}", sp.getSpid());
      return null;
    }

    String dueDate = dueDate(sp);
    CinntraPassbookInvoiceRequest request =
        buildInvoiceRequest(
            hub,
            sapMap,
            seriesCode,
            dueDate,
            orderId,
            sp.getAmount(),
            costingCode,
            "SER000005",
            "Comission");
    CinntraPassbookInvoiceRequest requestCopy =
        buildInvoiceCopy(sp, hub, spDetail, request, seriesCode, "Comission", true);
    return sapClient.postWithAddressRetry(
        invoicePath(requestCopy, spDetail),
        requestCopy,
        orderId,
        57,
        null,
        spDetail.getId(),
        hub,
        sapMap != null ? sapMap.getSapId() : null);
  }

  /** Reason 102: SP convenience-fee AR invoice (requestType 102), as a draft for Notion SPs. */
  private CinntraPassbookSapResult postConvenience(
      CinntraPassbookEntryRow sp, CinntraPassbookLookups lookups) {
    Integer amount = sp.getAmount();
    String dueDate = dueDate(sp);
    Long orderId = sp.getOrderId() != null ? sp.getOrderId() : 0L;
    CinntraPassbookSpRow spDetail = requireSp(sp, lookups);
    CinntraPassbookHubRow hub = lookups.hub(spDetail.getHubId());
    String costingCode = requireOrderCostingCode(sp, lookups);

    if (lookups.isExcludedCategorySp(spDetail.getId())) {
      log.info(
          "Skipping order id {} because SP {} is working on an excluded category.",
          orderId,
          spDetail.getId());
      return null;
    }
    if (isFranchiseOrLaserSp(hub, spDetail)) {
      return null;
    }

    CinntraPassbookSapMappingRow sapMap =
        lookups.sapMapping(spDetail.getId(), CinntraPassbookReader.SAP_MAPPING_TYPE_SP);
    CinntraPassbookSapSeriesRow seriesCode = lookups.series(hub.getSapWhsCode());
    if (seriesCode == null) {
      log.warn("Skipping record, seriesCode not found for spId={}", sp.getSpid());
      return null;
    }

    CinntraPassbookInvoiceRequest request =
        buildInvoiceRequest(
            hub, sapMap, seriesCode, dueDate, orderId, amount, costingCode, "SER000024", "Conven.");
    CinntraPassbookInvoiceRequest requestCopy =
        buildInvoiceCopy(sp, hub, spDetail, request, seriesCode, "Conven.", false);
    return sapClient.postWithAddressRetry(
        invoicePath(requestCopy, spDetail),
        requestCopy,
        orderId,
        102,
        null,
        spDetail.getId(),
        hub,
        sapMap != null ? sapMap.getSapId() : null);
  }

  /**
   * Reason 26: SP wallet recharge as an incoming payment (requestType 59), keyed on the passbook id
   * because a recharge has no order.
   */
  private CinntraPassbookSapResult postWalletRecharge(
      CinntraPassbookEntryRow sp, CinntraPassbookLookups lookups) {
    String dueDate = dueDate(sp);
    CinntraPassbookSpRow spDetail = requireSp(sp, lookups);
    CinntraPassbookHubRow hub = lookups.hub(spDetail.getHubId());

    if (isFranchiseOrLaserSp(hub, spDetail)) {
      return null;
    }

    CinntraPassbookSapMappingRow sapMap =
        lookups.sapMapping(spDetail.getId(), CinntraPassbookReader.SAP_MAPPING_TYPE_SP);
    CinntraPassbookSapSeriesRow seriesCode = lookups.series(hub.getSapWhsCode());
    if (seriesCode == null) {
      log.warn("Skipping record, seriesCode not found for spId={}", sp.getSpid());
      return null;
    }

    CinntraPassbookPaymentRequest incomingPayment = new CinntraPassbookPaymentRequest();
    incomingPayment.setDocNum(0);
    incomingPayment.setDocType("rCustomer");
    incomingPayment.setDocTypte("rCustomer");
    incomingPayment.setDocDate(dueDate);
    incomingPayment.setCardCode(
        sapMap != null && sapMap.getSapId() != null ? sapMap.getSapId() : "");
    incomingPayment.setDocCurrency("INR");
    incomingPayment.setCashSum(0.0);
    incomingPayment.setTransferAccount("14512004");
    incomingPayment.setTransferSum(sp.getAmount());
    incomingPayment.setTransferDate(dueDate);
    incomingPayment.setDocRate(0.0);
    incomingPayment.setCounterReference("Sp Wallet Recharge");
    incomingPayment.setTaxDate(dueDate);
    incomingPayment.setSeries(seriesCode.getIncomingPaymentSeries());
    incomingPayment.setDiscountPercent(0.0);
    incomingPayment.setCashSumFc(0.0);
    incomingPayment.setCashSumSys(0.0);
    incomingPayment.setIsPayToBank("tNO");
    incomingPayment.setDueDate(dueDate);
    incomingPayment.setBplId(hub.getSapBplId() != null ? hub.getSapBplId() : 0);
    incomingPayment.setDocObjectCode("24");

    return sapClient.postWithAddressRetry(
        "IncomingPayments",
        incomingPayment,
        sp.getId(),
        59,
        null,
        spDetail.getId(),
        hub,
        sapMap != null ? sapMap.getSapId() : null);
  }

  /**
   * Reason 27: online booking payment as an incoming payment (requestType 55). Needs an SP mapping.
   */
  private CinntraPassbookSapResult postBookingOnline(
      CinntraPassbookEntryRow sp, CinntraPassbookLookups lookups) {
    String dueDate = dueDate(sp);
    CinntraPassbookSpRow spDetail = requireSp(sp, lookups);
    CinntraPassbookHubRow hub = lookups.hub(spDetail.getHubId());

    if (hub != null && Boolean.TRUE.equals(hub.getIsFranchise())) {
      log.info("Skipping franchise hub SP ID: {}, Hub ID: {}", sp.getSpid(), hub.getId());
      return null;
    }
    if (lookups.isExcludedCategorySp(spDetail.getId())) {
      log.info(
          "Skipping order id {} because SP {} is working on an excluded category.",
          sp.getOrderId(),
          spDetail.getId());
      return null;
    }

    CinntraPassbookSapMappingRow sapMap =
        lookups.sapMapping(spDetail.getId(), CinntraPassbookReader.SAP_MAPPING_TYPE_SP);
    if (sapMap == null) {
      return null;
    }
    if (hub == null) {
      throw new IllegalStateException(
          "Hub " + spDetail.getHubId() + " not found for SP " + spDetail.getId());
    }
    CinntraPassbookSapSeriesRow seriesCode = lookups.series(hub.getSapWhsCode());
    if (seriesCode == null) {
      log.warn("Skipping record, seriesCode not found for spId={}", sp.getSpid());
      return null;
    }

    CinntraPassbookPaymentRequest incomingPayment = new CinntraPassbookPaymentRequest();
    incomingPayment.setDocType("rCustomer");
    incomingPayment.setDocDate(dueDate);
    incomingPayment.setCardCode(sapMap.getSapId());
    incomingPayment.setDocCurrency("INR");
    incomingPayment.setCashSum(0.0);
    incomingPayment.setTransferAccount("21511001");
    incomingPayment.setTransferSum(sp.getAmount());
    incomingPayment.setTransferDate(dueDate);
    incomingPayment.setDocRate(0.0);
    incomingPayment.setTaxDate(dueDate);
    incomingPayment.setSeries(seriesCode.getIncomingPaymentSeries());
    incomingPayment.setDiscountPercent(0.0);
    incomingPayment.setIsPayToBank("tNO");
    incomingPayment.setDocTypte("rCustomer");
    incomingPayment.setDueDate(dueDate);
    incomingPayment.setBplId(hub.getSapBplId());
    incomingPayment.setUti(sp.getPaytmtrnxid() != null ? sp.getPaytmtrnxid() : "");
    incomingPayment.setUoi(sp.getOrderId() != null ? sp.getOrderId() : 0L);
    incomingPayment.setDocObjectCode("24");

    return sapClient.postWithAddressRetry(
        "IncomingPayments",
        incomingPayment,
        sp.getOrderId(),
        55,
        null,
        spDetail.getId(),
        hub,
        sapMap.getSapId());
  }

  private static CinntraPassbookSpRow requireSp(
      CinntraPassbookEntryRow sp, CinntraPassbookLookups lookups) {
    CinntraPassbookSpRow spDetail = lookups.sp(sp.getSpid());
    if (spDetail == null) {
      throw new IllegalStateException("SP " + sp.getSpid() + " not found");
    }
    return spDetail;
  }

  /**
   * tracking loads the order before any eligibility check and dereferences it, so a missing order
   * fails the entry.
   */
  private static String requireOrderCostingCode(
      CinntraPassbookEntryRow sp, CinntraPassbookLookups lookups) {
    if (!lookups.orderExists(sp.getOrderId())) {
      throw new IllegalStateException("Order " + sp.getOrderId() + " not found");
    }
    return lookups.costingCode(sp.getOrderId());
  }

  /** tracking's {@code hub.getIs_franchise() || laser} — throws where that unboxing would. */
  private static boolean isFranchiseOrLaserSp(
      CinntraPassbookHubRow hub, CinntraPassbookSpRow spDetail) {
    if (hub == null) {
      throw new IllegalStateException(
          "Hub " + spDetail.getHubId() + " not found for SP " + spDetail.getId());
    }
    if (hub.getIsFranchise() == null) {
      throw new IllegalStateException("tbl_hub.is_franchise is null for hub " + hub.getId());
    }
    return hub.getIsFranchise() || isLaserSp(spDetail);
  }

  /** The SP-name exclusions tracking applies to commission only (laser / leaser / laeser / bt). */
  private static boolean isExcludedCommissionSpName(CinntraPassbookSpRow spDetail) {
    String name = spDetail.getName() != null ? spDetail.getName().toLowerCase() : "";
    return name.contains("laser")
        || name.contains("leaser")
        || name.contains("laeser")
        || name.contains("bt");
  }

  /** Notion SPs get a Draft (oInvoices / doctype 13) instead of a straight Invoice. */
  private static String invoicePath(
      CinntraPassbookInvoiceRequest requestCopy, CinntraPassbookSpRow spDetail) {
    if (!isNotionSp(spDetail)) {
      return "Invoices";
    }
    requestCopy.setDocObjectCode("oInvoices");
    requestCopy.setDocType("13");
    return "Drafts";
  }

  private static String dueDate(CinntraPassbookEntryRow sp) {
    return sp.getDate().toLocalDate().format(DOC_DATE_FORMATTER);
  }

  /**
   * tracking's {@code buildPassbookPurchaseInvoiceRequest}. Only its fields are copied onward, but
   * it is built in full because building it is where a missing sales-order series fails the entry.
   */
  private static CinntraPassbookInvoiceRequest buildInvoiceRequest(
      CinntraPassbookHubRow hub,
      CinntraPassbookSapMappingRow sapMap,
      CinntraPassbookSapSeriesRow seriesCode,
      String dueDate,
      Long orderId,
      Integer amount,
      String costingCode,
      String itemCode,
      String utos) {
    CinntraPassbookInvoiceRequest request = new CinntraPassbookInvoiceRequest();
    request.setDocType("dDocument_Items");
    request.setDocDate(dueDate);
    request.setDocDueDate(dueDate);
    if (sapMap != null && sapMap.getSapId() != null) {
      request.setCardCode(sapMap.getSapId());
    } else {
      log.warn("sapMap or sapId is null. Skipping setCardCode.");
      request.setCardCode("");
    }
    request.setSeries(seriesCode.getSalesOrderSeries());
    request.setTaxDate(dueDate);
    request.setGstTransactionType("gsttrantyp_GSTTaxInvoice");
    request.setBplIdAssignedToInvoice(hub.getSapBplId() != null ? hub.getSapBplId() : 0);
    request.setNumAtCard(orderId.toString());
    request.setComments("");
    request.setDocObjectCode("");
    request.setShipToCode(hub.getHub());
    request.setPayToCode(hub.getHub());
    request.setUtos(utos);
    request.setDocumentLines(buildDocumentLines(hub, amount, costingCode, itemCode));
    return request;
  }

  private static List<CinntraPassbookDocumentLine> buildDocumentLines(
      CinntraPassbookHubRow hub, Integer amount, String costingCode, String itemCode) {
    CinntraPassbookDocumentLine line = new CinntraPassbookDocumentLine();
    line.setItemCode(itemCode);
    line.setQuantity(1.0);
    double grossAmount = amount;
    line.setUnitPrice(grossAmount / 1.18);
    line.setCurrency("INR");
    line.setDiscountPercent(0.0);
    line.setWarehouseCode(hub.getSapWhsCode() != null ? hub.getSapWhsCode() : "");
    line.setTreeType("iNotATree");
    line.setFreeText("");
    line.setBaseType(-1);
    line.setTaxCode(resolveHubTaxCode(hub));
    line.setCostingCode(costingCode);

    List<CinntraPassbookDocumentLine> lines = new ArrayList<>();
    lines.add(line);
    return lines;
  }

  private static String resolveHubTaxCode(CinntraPassbookHubRow hub) {
    if (hub.getIgst() != null && hub.getIgst() > 0) {
      return "IGST@" + hub.getIgst().intValue();
    }
    if (hub.getCgst() != null && hub.getSgst() != null && hub.getCgst() > 0 && hub.getSgst() > 0) {
      return "CG+SG@" + Math.round(hub.getCgst() + hub.getSgst());
    }
    return null;
  }

  /**
   * tracking's {@code buildPassbookInvoiceCopy} — the request actually sent, on the AR-invoice
   * series.
   */
  private static CinntraPassbookInvoiceRequest buildInvoiceCopy(
      CinntraPassbookEntryRow sp,
      CinntraPassbookHubRow hub,
      CinntraPassbookSpRow spDetail,
      CinntraPassbookInvoiceRequest request,
      CinntraPassbookSapSeriesRow seriesCode,
      String utos,
      boolean includeVoucherNumber) {
    CinntraPassbookInvoiceRequest requestCopy = new CinntraPassbookInvoiceRequest();
    requestCopy.setDocType(request.getDocType());
    requestCopy.setDocDate(request.getDocDate());
    requestCopy.setDocDueDate(request.getDocDueDate());
    requestCopy.setCardCode(request.getCardCode());
    requestCopy.setSeries(seriesCode.getArInvoiceSeries());
    requestCopy.setTaxDate(request.getTaxDate());
    requestCopy.setBplIdAssignedToInvoice(request.getBplIdAssignedToInvoice());
    requestCopy.setUtos(utos);
    requestCopy.setDocumentLines(request.getDocumentLines());
    requestCopy.setNumAtCard(request.getNumAtCard());
    requestCopy.setComments("");
    requestCopy.setDocObjectCode("");
    requestCopy.setGstTransactionType("gsttrantyp_GSTTaxInvoice");
    requestCopy.setShipToCode(hub.getHub());
    requestCopy.setPayToCode(hub.getHub());
    requestCopy.setUconsignee(spDetail.getName());
    if (includeVoucherNumber) {
      requestCopy.setVoucherNumber(sp.getId() + 1000);
    }
    return requestCopy;
  }
}
