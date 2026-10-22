package com.yesmadam.schedulerservice.jobs.cinntrapassbook.service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookChunkResult;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookJournalEntry;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookJournalEntryLine;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookJournalVoucherRequest;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookLookups;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookPaymentRequest;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSapResult;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookEntryRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookHubRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookSapMappingRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookSapSeriesRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookSpRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.repository.CinntraPassbookWriteRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * One passbook entry through tracking's 30-minute sync — the body of {@code
 * CinntraPassbook#processPassbookEntries}, branch for branch:
 *
 * <ul>
 *   <li>43 DEBIT (SP self withdrawal): {@code VendorPayments}, requestType 42.
 *   <li>14 / 24 (other debit / credit): {@code JournalVouchersService_Add}, requestType 43.
 *   <li>41 / 42 (commission to saving and back): {@code JournalEntries}, requestType 52.
 *   <li>40 (product purchase by wallet): a material {@code JournalEntries} post, requestType 3, and
 *       then <b>also</b> the general entry below — tracking's reason-40 block has no {@code
 *       continue}, so it falls through. Reproduced, not fixed: whether that second post is intended
 *       is a finance question, not a migration one.
 *   <li>Everything else: {@code JournalEntries} against the reason code's account from {@code
 *       tbl_payment_action_sap}, requestType 44; skipped when the reason has no account.
 * </ul>
 *
 * <p>Every post records its outcome in {@code is_sap_processed} (1 on 2xx, 0 otherwise), which is
 * both what the next runs' retry window selects on and what a retried chunk skips on.
 *
 * <p>One deliberate difference, in accounting only: tracking counts a branch as a success once the
 * call returns, whatever SAP answered. Here a post counts as succeeded only on a 2xx, so the run's
 * counts say what actually reached SAP. What is sent, and what is written, is unchanged.
 */
@Slf4j
@Component
public class CinntraPassbookJournalPoster {

  private static final DateTimeFormatter DOC_DATE_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd");

  /** SP wallet account every passbook movement is booked against. */
  private static final String SP_WALLET_ACCOUNT = "12111008";

  /** tracking's {@code PaymentAction} labels, used as the general entry's memo. */
  private static final Map<Integer, String> PAYMENT_ACTION_LABELS =
      Map.ofEntries(
          Map.entry(11, "Commission"),
          Map.entry(12, "Fine on Beautician"),
          Map.entry(13, "Rework"),
          Map.entry(14, "Others Debit"),
          Map.entry(15, "Tax Debit"),
          Map.entry(16, "Credit Against Kit"),
          Map.entry(21, "Transport"),
          Map.entry(22, "Coupon Code"),
          Map.entry(23, "Wallet Redeem"),
          Map.entry(24, "Others Credit"),
          Map.entry(25, "Tax Credit"),
          Map.entry(26, "Recharge Wallet"),
          Map.entry(27, "Booking Online Transaction"),
          Map.entry(28, "Rework by Company"),
          Map.entry(29, "Debit Against Kit"),
          Map.entry(32, "Product Wallet Cashback"),
          Map.entry(33, "Service Cashback"),
          Map.entry(34, "Refer Cashback"),
          Map.entry(35, "Exclusive Cashback"),
          Map.entry(40, "Product Purchase By Wallet"),
          Map.entry(41, "Amount Added to Saving Wallet"),
          Map.entry(42, "Amount Added to Main Wallet"),
          Map.entry(43, "SP Self Withdrawal"),
          Map.entry(44, "Free Product Cashback"),
          Map.entry(45, "Elite Benefit Cashback"),
          Map.entry(46, "Free Cleanup Cashback"),
          Map.entry(47, "TDS Amount"),
          Map.entry(48, "Referral Wallet Redeem"),
          Map.entry(49, "Prepaid Discount Cashback"),
          Map.entry(50, "Free Service Cashback"));

  private final CinntraPassbookSapClient sapClient;
  private final CinntraPassbookWriteRepository writeRepository;

  public CinntraPassbookJournalPoster(
      CinntraPassbookSapClient sapClient, CinntraPassbookWriteRepository writeRepository) {
    this.sapClient = sapClient;
    this.writeRepository = writeRepository;
  }

  /** Posts {@code sp} and adds its outcome to {@code result}. Never throws. */
  public void post(
      CinntraPassbookEntryRow sp,
      CinntraPassbookLookups lookups,
      CinntraPassbookChunkResult result) {
    try {
      if (sp.getSpid() == null
          || sp.getReasonCode() == null
          || sp.getAmount() == null
          || sp.getActiontype() == null
          || sp.getDate() == null) {
        log.warn("Skipping invalid SpPassbook entry: {}", sp);
        failed(result);
        return;
      }

      int reasonCode = sp.getReasonCode();
      Integer amount = sp.getAmount();
      String actionType = sp.getActiontype();
      String dueDate = sp.getDate().toLocalDate().format(DOC_DATE_FORMATTER);
      Long orderId = sp.getOrderId() != null ? sp.getOrderId() : 0L;

      CinntraPassbookSpRow spDetail = lookups.sp(sp.getSpid());
      if (spDetail == null) {
        log.warn("SPRegistration not found for SP ID: {}", sp.getSpid());
        failed(result);
        return;
      }

      CinntraPassbookHubRow hub = lookups.hub(spDetail.getHubId());
      if (hub != null && Boolean.TRUE.equals(hub.getIsFranchise())) {
        log.info("Skipping franchise hub SP ID: {}, Hub ID: {}", sp.getSpid(), hub.getId());
        skipped(result);
        return;
      }

      CinntraPassbookSapMappingRow sapMap =
          lookups.sapMapping(sp.getSpid(), CinntraPassbookReader.SAP_MAPPING_TYPE_SP);
      if (lookups.isExcludedCategorySp(spDetail.getId())) {
        log.info(
            "Skipping order id {} because SP {} is working on an excluded category.",
            orderId,
            spDetail.getId());
        skipped(result);
        return;
      }

      if (CinntraPassbookDocumentPoster.isLaserSp(spDetail)) {
        // tracking counts a laser SP's entry as a success without posting it.
        result.setSucceeded(result.getSucceeded() + 1);
        return;
      }

      if (reasonCode == 43 && "DEBIT".equalsIgnoreCase(actionType)) {
        postVendorPayment(sp, hub, sapMap, dueDate, orderId, lookups, result);
        return;
      }
      if (reasonCode == 14 || reasonCode == 24) {
        postJournalVoucher(sp, hub, sapMap, dueDate, orderId, result);
        return;
      }
      if (reasonCode == 41 || reasonCode == 42) {
        postSavingTransfer(sp, spDetail, hub, sapMap, dueDate, orderId, lookups, result);
        return;
      }
      if (reasonCode == 40) {
        postMaterialEntry(sp, spDetail, hub, sapMap, dueDate, orderId, lookups, result);
        // No return: tracking falls through to the general entry. See class Javadoc.
      }
      postGeneralEntry(sp, hub, sapMap, dueDate, orderId, lookups, result);
    } catch (Exception e) {
      failed(result);
      log.error(
          "Unexpected error processing passbook entry for SP ID: {}",
          sp.getSpid() != null ? sp.getSpid() : "Unknown",
          e);
    }
  }

  /** Reason 43 DEBIT: the SP withdrew from their wallet. */
  private void postVendorPayment(
      CinntraPassbookEntryRow sp,
      CinntraPassbookHubRow hub,
      CinntraPassbookSapMappingRow sapMap,
      String dueDate,
      Long orderId,
      CinntraPassbookLookups lookups,
      CinntraPassbookChunkResult result) {
    try {
      if (hub == null || hub.getSapWhsCode() == null) {
        log.warn("Missing hub or warehouse code for SP ID: {}", sp.getSpid());
        failed(result);
        return;
      }
      CinntraPassbookSapSeriesRow seriesCode = lookups.series(hub.getSapWhsCode());
      if (seriesCode == null) {
        log.warn(
            "Series code not found for warehouse: {} - SP ID: {}",
            hub.getSapWhsCode(),
            sp.getSpid());
        failed(result);
        return;
      }

      CinntraPassbookPaymentRequest payment = new CinntraPassbookPaymentRequest();
      payment.setDocType("rCustomer");
      payment.setDocDate(dueDate);
      payment.setCardCode(sapMap != null && sapMap.getSapId() != null ? sapMap.getSapId() : "");
      payment.setTransferAccount("14511002");
      payment.setTransferSum(sp.getAmount());
      payment.setSeries(seriesCode.getOutgoingPaymentSeries());
      payment.setBplId(hub.getSapBplId());
      payment.setDocObjectCode("46");
      record(sp, sapClient.post("VendorPayments", payment, orderId, 42, null), result);
    } catch (Exception e) {
      log.error("Vendor payment failed for SP ID: {}", sp.getSpid(), e);
      failed(result);
    }
  }

  /** Reasons 14 (other debit) and 24 (other credit). */
  private void postJournalVoucher(
      CinntraPassbookEntryRow sp,
      CinntraPassbookHubRow hub,
      CinntraPassbookSapMappingRow sapMap,
      String dueDate,
      Long orderId,
      CinntraPassbookChunkResult result) {
    try {
      if (hub == null || hub.getSapBplId() == null) {
        log.warn("Missing hub or BPL ID for SP ID: {}", sp.getSpid());
        failed(result);
        return;
      }

      CinntraPassbookJournalVoucherRequest.JournalEntry journalEntry =
          new CinntraPassbookJournalVoucherRequest.JournalEntry();
      journalEntry.setDueDate(dueDate);
      journalEntry.setReferenceDate(dueDate);
      journalEntry.setVoucherNumber(sp.getId() + 1000);

      String amount = sp.getAmount().toString();
      String memo = limitTo100Chars(sp.getComment() != null ? sp.getComment() : "");
      String cardCode = sapMap != null ? sapMap.getSapId() : null;
      boolean otherDebit = sp.getReasonCode() == 14;
      journalEntry.setMemo(otherDebit ? "Other Debit" : "Other Credit");
      // 14 debits the SP wallet against 52311008; 24 credits it against 52311007.
      journalEntry.setJournalEntryLines(
          otherDebit
              ? List.of(
                  voucherLine(hub, SP_WALLET_ACCOUNT, "0", amount, memo, cardCode),
                  voucherLine(hub, "52311008", amount, "0", memo, null))
              : List.of(
                  voucherLine(hub, "52311007", "0", amount, memo, null),
                  voucherLine(hub, SP_WALLET_ACCOUNT, amount, "0", memo, cardCode)));

      CinntraPassbookJournalVoucherRequest.JournalVoucher journalVoucher =
          new CinntraPassbookJournalVoucherRequest.JournalVoucher();
      journalVoucher.setJournalEntry(journalEntry);
      CinntraPassbookJournalVoucherRequest request = new CinntraPassbookJournalVoucherRequest();
      request.setJournalVoucher(journalVoucher);

      record(sp, sapClient.post("JournalVouchersService_Add", request, orderId, 43, null), result);
    } catch (Exception e) {
      log.error("Journal voucher processing failed for SP ID: {}", sp.getSpid(), e);
      failed(result);
    }
  }

  private static CinntraPassbookJournalVoucherRequest.JournalEntryLine voucherLine(
      CinntraPassbookHubRow hub,
      String accountCode,
      String credit,
      String debit,
      String lineMemo,
      String shortName) {
    CinntraPassbookJournalVoucherRequest.JournalEntryLine line =
        new CinntraPassbookJournalVoucherRequest.JournalEntryLine();
    line.setBplId(hub.getSapBplId());
    line.setAccountCode(accountCode);
    line.setCredit(credit);
    line.setDebit(debit);
    line.setLineMemo(lineMemo);
    line.setShortName(shortName);
    line.setReference1("");
    line.setContraAccount("");
    return line;
  }

  /** Reasons 41 (commission to saving wallet) and 42 (saving to commission wallet). */
  private void postSavingTransfer(
      CinntraPassbookEntryRow sp,
      CinntraPassbookSpRow spDetail,
      CinntraPassbookHubRow hub,
      CinntraPassbookSapMappingRow sapMap,
      String dueDate,
      Long orderId,
      CinntraPassbookLookups lookups,
      CinntraPassbookChunkResult result) {
    boolean toSaving = sp.getReasonCode() == 41;
    log.info("{} for {}", toSaving ? "Commission to Saving" : "Saving to Commission", orderId);
    try {
      if (hub == null || hub.getSapBplId() == null) {
        log.warn("Missing hub or BPL ID for commission processing - SP ID: {}", sp.getSpid());
        failed(result);
        return;
      }

      CinntraPassbookSapMappingRow savingMap =
          lookups.sapMapping(spDetail.getId(), CinntraPassbookReader.SAP_MAPPING_TYPE_SAVING);
      String spCardCode = sapMap != null ? sapMap.getSapId() : null;
      String savingCardCode = savingMap != null ? savingMap.getSapId() : null;
      int amount = sp.getAmount();

      CinntraPassbookJournalEntryLine debitLine = new CinntraPassbookJournalEntryLine();
      debitLine.setAccountCode(toSaving ? SP_WALLET_ACCOUNT : "12111006");
      debitLine.setDebit(amount);
      debitLine.setCredit(0.0);
      debitLine.setDueDate(dueDate);
      debitLine.setShortName(toSaving ? spCardCode : savingCardCode);
      debitLine.setBplid(hub.getSapBplId());

      CinntraPassbookJournalEntryLine creditLine = new CinntraPassbookJournalEntryLine();
      creditLine.setAccountCode(toSaving ? "12111006" : SP_WALLET_ACCOUNT);
      creditLine.setDebit(0.0);
      creditLine.setCredit(amount);
      creditLine.setDueDate(dueDate);
      creditLine.setShortName(toSaving ? savingCardCode : spCardCode);
      creditLine.setLineMemo("");
      creditLine.setReference1("");
      creditLine.setReference2("");
      creditLine.setBplid(hub.getSapBplId());

      CinntraPassbookJournalEntry entry =
          transferEntry(
              sp, toSaving ? "Commission To Saving" : "Saving To Commission", dueDate, orderId);
      entry.setJournalEntryLines(List.of(debitLine, creditLine));
      record(sp, sapClient.post("JournalEntries", entry, orderId, 52, null), result);
    } catch (Exception e) {
      log.error("Commission to saving processing failed for SP ID: {}", sp.getSpid(), e);
      failed(result);
    }
  }

  /**
   * Reason 40: the wallet paid for material. tracking dereferences both SAP mappings without a null
   * check, so an SP missing either one fails here — and the general entry still follows.
   */
  private void postMaterialEntry(
      CinntraPassbookEntryRow sp,
      CinntraPassbookSpRow spDetail,
      CinntraPassbookHubRow hub,
      CinntraPassbookSapMappingRow sapMap,
      String dueDate,
      Long orderId,
      CinntraPassbookLookups lookups,
      CinntraPassbookChunkResult result) {
    log.info("Commission to Material for{}", orderId);
    try {
      CinntraPassbookSapMappingRow materialMap =
          lookups.sapMapping(spDetail.getId(), CinntraPassbookReader.SAP_MAPPING_TYPE_MATERIAL);
      if (sapMap == null || materialMap == null) {
        throw new IllegalStateException(
            "SAP mapping 106 or 104 missing for SP " + spDetail.getId());
      }
      int amount = sp.getAmount();
      boolean hasBpl = hub != null && hub.getSapBplId() != null;

      CinntraPassbookJournalEntryLine debitLine = new CinntraPassbookJournalEntryLine();
      debitLine.setAccountCode(SP_WALLET_ACCOUNT);
      debitLine.setDebit(amount);
      debitLine.setCredit(0.0);
      debitLine.setDueDate(dueDate);
      if (sapMap.getSapId() != null) {
        debitLine.setShortName(sapMap.getSapId());
      }
      if (hasBpl) {
        debitLine.setBplid(hub.getSapBplId());
      }

      CinntraPassbookJournalEntryLine creditLine = new CinntraPassbookJournalEntryLine();
      creditLine.setAccountCode("12111003");
      creditLine.setDebit(0.0);
      creditLine.setCredit(amount);
      creditLine.setDueDate(dueDate);
      if (materialMap.getSapId() != null) {
        creditLine.setShortName(materialMap.getSapId());
      }
      creditLine.setLineMemo("");
      creditLine.setReference1("");
      creditLine.setReference2("");
      if (hasBpl) {
        creditLine.setBplid(hub.getSapBplId());
      }

      CinntraPassbookJournalEntry entry =
          transferEntry(sp, "Commission to Material", dueDate, orderId);
      entry.setJournalEntryLines(List.of(debitLine, creditLine));
      record(sp, sapClient.post("JournalEntries", entry, orderId, 3, null), result);
    } catch (Exception e) {
      log.error("Commission to material processing failed for SP ID: {}", sp.getSpid(), e);
      failed(result);
    }
  }

  /** The header shared by the 40 / 41 / 42 entries. */
  private static CinntraPassbookJournalEntry transferEntry(
      CinntraPassbookEntryRow sp, String memo, String dueDate, Long orderId) {
    CinntraPassbookJournalEntry entry = new CinntraPassbookJournalEntry();
    entry.setMemo(memo);
    entry.setTaxDate(dueDate);
    entry.setSeries(771);
    entry.setDueDate(dueDate);
    entry.setOriginalJournal("ttARInvoice");
    entry.setOriginal(107);
    entry.setUoi(orderId);
    entry.setVoucherNumber(sp.getId() + 1000);
    return entry;
  }

  /** Any other reason code, booked against its {@code tbl_payment_action_sap} account. */
  private void postGeneralEntry(
      CinntraPassbookEntryRow sp,
      CinntraPassbookHubRow hub,
      CinntraPassbookSapMappingRow sapMap,
      String dueDate,
      Long orderId,
      CinntraPassbookLookups lookups,
      CinntraPassbookChunkResult result) {
    int reasonCode = sp.getReasonCode();
    try {
      String accountCode = lookups.accountCode(reasonCode);
      if (accountCode == null || accountCode.isEmpty()) {
        log.warn(
            "Skipping entry due to missing account code for reasonCode={}, SP ID: {}",
            reasonCode,
            sp.getSpid());
        skipped(result);
        return;
      }

      CinntraPassbookJournalEntry entry = new CinntraPassbookJournalEntry();
      entry.setMemo(PAYMENT_ACTION_LABELS.getOrDefault(reasonCode, "Unknown"));
      entry.setReference2(limitTo100Chars(sp.getComment()));
      entry.setTaxDate(dueDate);
      entry.setDueDate(dueDate);
      entry.setSeries(771);
      entry.setOriginalJournal("ttARInvoice");
      entry.setOriginal(107);
      entry.setReferenceDate(dueDate);
      entry.setUoi(orderId);
      entry.setVoucherNumber(sp.getId() + 1000);

      int amount = sp.getAmount();
      String cardCode = sapMap != null && sapMap.getSapId() != null ? sapMap.getSapId() : "";
      CinntraPassbookJournalEntryLine debitLine = new CinntraPassbookJournalEntryLine();
      CinntraPassbookJournalEntryLine creditLine = new CinntraPassbookJournalEntryLine();
      if ("DEBIT".equalsIgnoreCase(sp.getActiontype())) {
        creditLine.setAccountCode(accountCode);
        creditLine.setCredit(amount);
        debitLine.setAccountCode(SP_WALLET_ACCOUNT);
        debitLine.setDebit(amount);
        debitLine.setShortName(cardCode);
      } else {
        debitLine.setAccountCode(accountCode);
        debitLine.setDebit(amount);
        creditLine.setAccountCode(SP_WALLET_ACCOUNT);
        creditLine.setCredit(amount);
        creditLine.setShortName(cardCode);
      }
      if (hub != null && hub.getSapBplId() != null) {
        debitLine.setBplid(hub.getSapBplId());
        creditLine.setBplid(hub.getSapBplId());
      }

      List<CinntraPassbookJournalEntryLine> lines = new ArrayList<>();
      lines.add(debitLine);
      lines.add(creditLine);
      entry.setJournalEntryLines(lines);

      record(sp, sapClient.post("JournalEntries", entry, orderId, 44, reasonCode), result);
    } catch (Exception e) {
      log.error("General journal entry processing failed for SP ID: {}", sp.getSpid(), e);
      failed(result);
    }
  }

  /**
   * tracking's {@code markSapProcessed} plus the count. A failed flag write is logged, as in
   * tracking.
   */
  private void record(
      CinntraPassbookEntryRow sp,
      CinntraPassbookSapResult sapResult,
      CinntraPassbookChunkResult result) {
    boolean processed =
        sapResult != null
            && sapResult.getResponseCode() >= 200
            && sapResult.getResponseCode() <= 300;
    try {
      writeRepository.updateSapProcessed(sp.getId(), processed ? 1 : 0);
      sp.setIsSapProcessed(processed ? 1 : 0);
    } catch (Exception e) {
      log.error("Failed to persist is_sap_processed for SpPassbook ID: {}", sp.getId(), e);
    }
    if (processed) {
      result.setSucceeded(result.getSucceeded() + 1);
    } else {
      failed(result);
    }
  }

  private static void failed(CinntraPassbookChunkResult result) {
    result.setFailed(result.getFailed() + 1);
  }

  private static void skipped(CinntraPassbookChunkResult result) {
    result.setSkipped(result.getSkipped() + 1);
  }

  private static String limitTo100Chars(String input) {
    return input != null && input.length() > 100 ? input.substring(0, 100) : input;
  }
}
