package com.yesmadam.schedulerservice.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yesmadam.schedulerservice.common.progress.ProgressReporter;
import com.yesmadam.schedulerservice.jobs.cinntraLoginAndStoreSession.service.CinntraLoginAndStoreSessionService;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookChunkResult;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSapDocEntry;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSapSession;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSelection;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSyncKind;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookAccountCodeRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookEntryRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookHubRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookOrderCostingProjection;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookSapMappingRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookSapSeriesRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookSpRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.repository.CinntraPassbookReadRepository;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.repository.CinntraPassbookSapDocEntryRepository;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.repository.CinntraPassbookSapErrorLogRepository;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.repository.CinntraPassbookSapSessionRepository;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.repository.CinntraPassbookWriteRepository;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.service.CinntraPassbookDocumentPoster;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.service.CinntraPassbookJournalPoster;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.service.CinntraPassbookReader;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.service.CinntraPassbookSapClient;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.service.CinntraPassbookSapSessionException;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.service.CinntraPassbookSyncService;

/**
 * Payload parity and retry safety for the cinntra-passbook syncs: the real service, posters, reader
 * and SAP client against a stub SAP server, with only the repositories mocked.
 */
class CinntraPassbookPayloadTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private static final int SP_ID = 501;
  private static final int HUB_ID = 2;
  private static final long ORDER_ID = 777_001L;
  private static final LocalDateTime ENTRY_DATE = LocalDateTime.of(2026, 10, 21, 14, 5);

  private SapStubServer sap;
  private CinntraPassbookReadRepository readRepository;
  private CinntraPassbookWriteRepository writeRepository;
  private CinntraPassbookSapDocEntryRepository docEntryRepository;
  private CinntraPassbookSapSessionRepository sessionRepository;
  private CinntraPassbookSapErrorLogRepository errorLogRepository;
  private CinntraLoginAndStoreSessionService loginService;
  private CinntraPassbookSyncService service;

  private final Map<Long, CinntraPassbookEntryRow> passbook = new HashMap<>();
  private final Map<Integer, CinntraPassbookSpRow> sps = new HashMap<>();
  private final Map<Integer, CinntraPassbookHubRow> hubs = new HashMap<>();
  private final List<CinntraPassbookSapMappingRow> mappings = new ArrayList<>();
  private final Set<Integer> excludedCategorySps = new HashSet<>();
  private final List<CinntraPassbookAccountCodeRow> accountCodes = new ArrayList<>();
  private final Map<Long, String> orderCosting = new HashMap<>();
  private final Set<Integer> postedKeys = new HashSet<>();

  @BeforeEach
  void setUp() throws Exception {
    sap = new SapStubServer().respondByDefault("{\"DocEntry\":9100}");
    readRepository = mock(CinntraPassbookReadRepository.class);
    writeRepository = mock(CinntraPassbookWriteRepository.class);
    docEntryRepository = mock(CinntraPassbookSapDocEntryRepository.class);
    sessionRepository = mock(CinntraPassbookSapSessionRepository.class);
    errorLogRepository = mock(CinntraPassbookSapErrorLogRepository.class);
    loginService = mock(CinntraLoginAndStoreSessionService.class);

    CinntraPassbookSapClient sapClient =
        new CinntraPassbookSapClient(
            sessionRepository,
            docEntryRepository,
            errorLogRepository,
            readRepository,
            loginService);
    ReflectionTestUtils.setField(sapClient, "cinntraBaseUrl", sap.baseUrl());
    ReflectionTestUtils.setField(sapClient, "cinntraKey", "k");
    ReflectionTestUtils.setField(sapClient, "cinntraUserId", "u");

    service =
        new CinntraPassbookSyncService(
            readRepository,
            writeRepository,
            new CinntraPassbookReader(readRepository),
            new CinntraPassbookJournalPoster(sapClient, writeRepository),
            new CinntraPassbookDocumentPoster(sapClient));

    sps.put(SP_ID, sp(SP_ID, "Asha Verma"));
    hubs.put(HUB_ID, hub());
    mappings.add(mapping(SP_ID, 106, "V-501"));
    mappings.add(mapping(SP_ID, 105, "S-501"));
    mappings.add(mapping(SP_ID, 104, "M-501"));
    orderCosting.put(ORDER_ID, "CC-7");
    wire();
  }

  @AfterEach
  void tearDown() {
    sap.close();
  }

  // ================================================================
  // Journal sync (every 30 minutes)
  // ================================================================

  @Test
  void selfWithdrawalPostsAVendorPaymentAndMarksTheEntryProcessed() throws Exception {
    entry(10L, 43, "DEBIT", 250, null);

    CinntraPassbookChunkResult result = sync(CinntraPassbookSyncKind.JOURNAL, 10L);

    JsonNode body = body("/VendorPayments");
    assertThat(body.get("DocType").asText()).isEqualTo("rCustomer");
    assertThat(body.get("DocDate").asText()).isEqualTo("2026-10-21");
    assertThat(body.get("CardCode").asText()).isEqualTo("V-501");
    assertThat(body.get("TransferAccount").asText()).isEqualTo("14511002");
    assertThat(body.get("TransferSum").asDouble()).isEqualTo(250.0);
    assertThat(body.get("Series").asInt()).isEqualTo(44);
    assertThat(body.get("BPLID").asInt()).isEqualTo(1);
    assertThat(body.get("DocObjectCode").asText()).isEqualTo("46");
    // No NON_NULL on tracking's PaymentRequestCopy: unset fields still go out.
    assertThat(body.has("U_TI")).isTrue();
    assertThat(result.getSucceeded()).isEqualTo(1);
    verify(writeRepository).updateSapProcessed(10L, 1);
    assertThat(savedDocEntry().getRequestType()).isEqualTo(42);
  }

  @Test
  void otherDebitPostsAJournalVoucherDebitingTheSpWallet() throws Exception {
    entry(11L, 14, "DEBIT", 300, "late kit return");

    sync(CinntraPassbookSyncKind.JOURNAL, 11L);

    JsonNode entry = body("/JournalVouchersService_Add").get("JournalVoucher").get("JournalEntry");
    assertThat(entry.get("Memo").asText()).isEqualTo("Other Debit");
    assertThat(entry.get("U_VNO").asLong()).isEqualTo(1011L);
    JsonNode wallet = entry.get("JournalEntryLines").get(0);
    assertThat(wallet.get("AccountCode").asText()).isEqualTo("12111008");
    assertThat(wallet.get("Debit").asText()).isEqualTo("300");
    assertThat(wallet.get("Credit").asText()).isEqualTo("0");
    assertThat(wallet.get("ShortName").asText()).isEqualTo("V-501");
    assertThat(wallet.get("LineMemo").asText()).isEqualTo("late kit return");
    JsonNode contra = entry.get("JournalEntryLines").get(1);
    assertThat(contra.get("AccountCode").asText()).isEqualTo("52311008");
    assertThat(contra.get("Credit").asText()).isEqualTo("300");
    assertThat(contra.get("ShortName").isNull()).isTrue();
  }

  @Test
  void commissionToSavingMovesTheAmountBetweenTheTwoWalletAccounts() throws Exception {
    entry(12L, 41, "DEBIT", 120, null);

    sync(CinntraPassbookSyncKind.JOURNAL, 12L);

    JsonNode entry = body("/JournalEntries");
    assertThat(entry.get("Memo").asText()).isEqualTo("Commission To Saving");
    assertThat(entry.get("Series").asInt()).isEqualTo(771);
    assertThat(entry.get("U_OI").asLong()).isEqualTo(ORDER_ID);
    JsonNode lines = entry.get("JournalEntryLines");
    assertThat(lines.get(0).get("AccountCode").asText()).isEqualTo("12111008");
    assertThat(lines.get(0).get("ShortName").asText()).isEqualTo("V-501");
    assertThat(lines.get(1).get("AccountCode").asText()).isEqualTo("12111006");
    assertThat(lines.get(1).get("ShortName").asText()).isEqualTo("S-501");
    assertThat(savedDocEntry().getRequestType()).isEqualTo(52);
  }

  @Test
  void productPurchaseByWalletPostsTheMaterialEntryAndThenTheGeneralEntryAsTrackingDoes()
      throws Exception {
    accountCodes.add(accountCode(40, "41000040"));
    entry(13L, 40, "DEBIT", 90, null);

    CinntraPassbookChunkResult result = sync(CinntraPassbookSyncKind.JOURNAL, 13L);

    List<JsonNode> posts = bodies("/JournalEntries");
    assertThat(posts)
        .extracting(p -> p.get("Memo").asText())
        .containsExactly("Commission to Material", "Product Purchase By Wallet");
    assertThat(posts.get(0).get("JournalEntryLines").get(1).get("ShortName").asText())
        .isEqualTo("M-501");
    assertThat(posts.get(1).get("JournalEntryLines").get(1).get("AccountCode").asText())
        .isEqualTo("41000040");
    assertThat(result.getSucceeded()).isEqualTo(2);
  }

  @Test
  void generalEntryIsSkippedWhenTheReasonHasNoAccountCode() {
    entry(14L, 12, "DEBIT", 50, null);

    CinntraPassbookChunkResult result = sync(CinntraPassbookSyncKind.JOURNAL, 14L);

    assertThat(sap.calls()).isEmpty();
    assertThat(result.getSkipped()).isEqualTo(1);
    verify(writeRepository, never()).updateSapProcessed(anyLong(), anyInt());
  }

  @Test
  void generalEntryCreditsTheSpWalletForACredit() throws Exception {
    accountCodes.add(accountCode(33, "52000033"));
    entry(15L, 33, "CREDIT", 75, null);

    sync(CinntraPassbookSyncKind.JOURNAL, 15L);

    JsonNode entry = body("/JournalEntries");
    assertThat(entry.get("Memo").asText()).isEqualTo("Service Cashback");
    JsonNode lines = entry.get("JournalEntryLines");
    assertThat(lines.get(0).get("AccountCode").asText()).isEqualTo("52000033");
    assertThat(lines.get(1).get("AccountCode").asText()).isEqualTo("12111008");
    assertThat(lines.get(1).get("ShortName").asText()).isEqualTo("V-501");
    assertThat(savedDocEntry().getJournalTypeCode()).isEqualTo(33);
  }

  @Test
  void excludedCategorySpsAreSkippedAndLaserSpsCountAsSuccessWithoutAPost() {
    accountCodes.add(accountCode(33, "52000033"));
    sps.put(502, sp(502, "Laser Studio"));
    sps.put(503, sp(503, "Neha"));
    excludedCategorySps.add(503);
    entry(16L, 33, "CREDIT", 75, null).setSpid(502);
    entry(17L, 33, "CREDIT", 75, null).setSpid(503);

    CinntraPassbookChunkResult result = sync(CinntraPassbookSyncKind.JOURNAL, 16L, 17L);

    assertThat(sap.calls()).isEmpty();
    assertThat(result.getSucceeded()).isEqualTo(1);
    assertThat(result.getSkipped()).isEqualTo(1);
  }

  @Test
  void aSapRejectionMarksTheEntryUnprocessedSoTheRetryWindowPicksItUp() throws Exception {
    sap.respondTo(
        "/VendorPayments", 400, "{\"error\":{\"message\":{\"value\":\"Invalid BP code\"}}}");
    entry(18L, 43, "DEBIT", 250, null);

    CinntraPassbookChunkResult result = sync(CinntraPassbookSyncKind.JOURNAL, 18L);

    assertThat(result.getFailed()).isEqualTo(1);
    verify(writeRepository).updateSapProcessed(18L, 0);
    verify(docEntryRepository, never()).save(any());
  }

  @Test
  void aSecondAttemptOfAChunkSkipsWhatTheFirstPosted() {
    accountCodes.add(accountCode(33, "52000033"));
    entry(19L, 33, "CREDIT", 75, null).setIsSapProcessed(1);
    entry(20L, 33, "CREDIT", 75, null);

    CinntraPassbookChunkResult result = sync(CinntraPassbookSyncKind.JOURNAL, 19L, 20L);

    assertThat(sap.calls()).hasSize(1);
    assertThat(result.getAlreadyPosted()).isEqualTo(1);
    assertThat(result.getSucceeded()).isEqualTo(1);
  }

  // ================================================================
  // Commission and convenience (01:00)
  // ================================================================

  @Test
  void commissionPostsAnArInvoiceOnTheHubsSeries() throws Exception {
    entry(30L, 11, "DEBIT", 1180, null);

    CinntraPassbookChunkResult result = sync(CinntraPassbookSyncKind.COMMISSION, 30L);

    JsonNode body = body("/Invoices");
    assertThat(body.get("DocType").asText()).isEqualTo("dDocument_Items");
    assertThat(body.get("CardCode").asText()).isEqualTo("V-501");
    assertThat(body.get("Series").asInt()).isEqualTo(22);
    assertThat(body.get("NumAtCard").asText()).isEqualTo(String.valueOf(ORDER_ID));
    assertThat(body.get("U_TOS").asText()).isEqualTo("Comission");
    assertThat(body.get("U_VNO").asLong()).isEqualTo(1030L);
    assertThat(body.get("U_Consignee").asText()).isEqualTo("Asha Verma");
    assertThat(body.get("ShipToCode").asText()).isEqualTo("DELHI");
    assertThat(body.has("OriginalRefNo")).isFalse();
    JsonNode line = body.get("DocumentLines").get(0);
    assertThat(line.get("ItemCode").asText()).isEqualTo("SER000005");
    assertThat(line.get("UnitPrice").asDouble()).isCloseTo(1000.0, Offset.offset(0.0001));
    assertThat(line.get("TaxCode").asText()).isEqualTo("CG+SG@18");
    assertThat(line.get("CostingCode3").asText()).isEqualTo("CC-7");
    assertThat(line.get("WarehouseCode").asText()).isEqualTo("DL-DL-1");

    assertThat(result.getSucceeded()).isEqualTo(1);
    assertThat(savedDocEntry().getRequestType()).isEqualTo(57);
    assertThat(savedDocEntry().getOrderId()).isEqualTo((int) ORDER_ID);
    verify(writeRepository).updateSapProcessed(30L, 1);
  }

  @Test
  void notionSpsGetADraftInsteadOfAnInvoice() throws Exception {
    sps.put(SP_ID, sp(SP_ID, "Notion Salon"));
    entry(31L, 11, "DEBIT", 1180, null);

    sync(CinntraPassbookSyncKind.COMMISSION, 31L);

    JsonNode body = body("/Drafts");
    assertThat(body.get("DocObjectCode").asText()).isEqualTo("oInvoices");
    assertThat(body.get("DocType").asText()).isEqualTo("13");
  }

  @Test
  void commissionSkipsBtAndLaserSpsByName() {
    sps.put(SP_ID, sp(SP_ID, "BT Studio"));
    entry(32L, 11, "DEBIT", 1180, null);

    CinntraPassbookChunkResult result = sync(CinntraPassbookSyncKind.COMMISSION, 32L);

    assertThat(sap.calls()).isEmpty();
    assertThat(result.getSkipped()).isEqualTo(1);
  }

  @Test
  void commissionFailsTheEntryWhenItsOrderIsMissingOrItsHubFranchiseFlagIsNull() {
    orderCosting.clear();
    entry(33L, 11, "DEBIT", 1180, null);

    CinntraPassbookChunkResult missingOrder = sync(CinntraPassbookSyncKind.COMMISSION, 33L);

    orderCosting.put(ORDER_ID, "CC-7");
    hubs.get(HUB_ID).setIsFranchise(null);
    CinntraPassbookChunkResult nullFranchise = sync(CinntraPassbookSyncKind.COMMISSION, 33L);

    assertThat(sap.calls()).isEmpty();
    assertThat(missingOrder.getFailed()).isEqualTo(1);
    assertThat(nullFranchise.getFailed()).isEqualTo(1);
  }

  @Test
  void oneCommissionDocumentPerOrderEvenAcrossRetries() {
    // 34 was posted by an earlier attempt (its doc entry exists); 35 shares 36's order.
    entry(34L, 11, "DEBIT", 1180, null).setOrderId(ORDER_ID + 1);
    orderCosting.put(ORDER_ID + 1, "CC-7");
    postedKeys.add((int) (ORDER_ID + 1));
    entry(35L, 11, "DEBIT", 1180, null);
    entry(36L, 11, "DEBIT", 590, null);

    CinntraPassbookChunkResult result = sync(CinntraPassbookSyncKind.COMMISSION, 34L, 35L, 36L);

    assertThat(sap.calls()).hasSize(1);
    assertThat(result.getSucceeded()).isEqualTo(1);
    assertThat(result.getAlreadyPosted()).isEqualTo(2);
    verify(writeRepository).updateSapProcessed(34L, 1);
    verify(writeRepository).updateSapProcessed(36L, 1);
  }

  @Test
  void convenienceUsesItsOwnItemAndOmitsTheVoucherNumber() throws Exception {
    entry(37L, 102, "DEBIT", 59, null);

    sync(CinntraPassbookSyncKind.CONVENIENCE, 37L);

    JsonNode body = body("/Invoices");
    assertThat(body.get("U_TOS").asText()).isEqualTo("Conven.");
    assertThat(body.has("U_VNO")).isFalse();
    assertThat(body.get("DocumentLines").get(0).get("ItemCode").asText()).isEqualTo("SER000024");
    assertThat(savedDocEntry().getRequestType()).isEqualTo(102);
  }

  @Test
  void noMatchingRecordPatchesTheSpAddressAndRetriesOnce() {
    sap.respondTo(
        "/Invoices",
        400,
        "{\"error\":{\"message\":{\"value\":\"No matching records found (ODBC -2028)\"}}}");
    entry(38L, 11, "DEBIT", 1180, null);

    CinntraPassbookChunkResult result = sync(CinntraPassbookSyncKind.COMMISSION, 38L);

    assertThat(sap.calls())
        .extracting(SapStubServer.Call::path)
        .containsExactly("/Invoices", "/BusinessPartners('V-501')", "/Invoices");
    assertThat(result.getFailed()).isEqualTo(1);
    verify(readRepository).findSpAddresses(SP_ID);
  }

  // ================================================================
  // Wallet recharge and booking online (04:00)
  // ================================================================

  @Test
  void walletRechargeIsKeyedOnThePassbookIdBecauseItHasNoOrder() throws Exception {
    entry(40L, 26, "CREDIT", 500, null).setOrderId(null);

    sync(CinntraPassbookSyncKind.WALLET_RECHARGE, 40L);

    JsonNode body = body("/IncomingPayments");
    assertThat(body.get("TransferAccount").asText()).isEqualTo("14512004");
    assertThat(body.get("CounterReference").asText()).isEqualTo("Sp Wallet Recharge");
    assertThat(body.get("Series").asInt()).isEqualTo(33);
    assertThat(body.get("DocCurrency").asText()).isEqualTo("INR");
    assertThat(savedDocEntry().getOrderId()).isEqualTo(40);
    assertThat(savedDocEntry().getRequestType()).isEqualTo(59);
  }

  @Test
  void bookingOnlineNeedsAnSpMappingAndCarriesTheTransactionId() throws Exception {
    entry(41L, 27, "CREDIT", 899, null).setPaytmtrnxid("PTM-1");

    sync(CinntraPassbookSyncKind.BOOKING_ONLINE, 41L);

    JsonNode body = body("/IncomingPayments");
    assertThat(body.get("TransferAccount").asText()).isEqualTo("21511001");
    assertThat(body.get("U_TI").asText()).isEqualTo("PTM-1");
    assertThat(body.get("U_OI").asLong()).isEqualTo(ORDER_ID);
    assertThat(savedDocEntry().getRequestType()).isEqualTo(55);

    mappings.clear();
    entry(42L, 27, "CREDIT", 899, null);
    CinntraPassbookChunkResult unmapped = sync(CinntraPassbookSyncKind.BOOKING_ONLINE, 42L);
    assertThat(unmapped.getSkipped()).isEqualTo(1);
  }

  // ================================================================
  // Chunk mechanics and selection
  // ================================================================

  @Test
  void reportsProgressOncePerEntryAndReturnsOnlyCountsAndOneTimingPerQuery() {
    accountCodes.add(accountCode(33, "52000033"));
    List<Long> ids = new ArrayList<>();
    for (long id = 50; id < 70; id++) {
      entry(id, 33, "CREDIT", 10, null);
      ids.add(id);
    }
    ids.add(9_999L);
    List<Integer> reported = new ArrayList<>();

    CinntraPassbookChunkResult result =
        service.syncChunk(CinntraPassbookSyncKind.JOURNAL, ids, reported::add);

    assertThat(reported).hasSize(21).endsWith(21);
    assertThat(result.getSucceeded()).isEqualTo(20);
    assertThat(result.getNotFound()).isEqualTo(1);
    // Bounded payload (rule 9): one entry per named query however many rows the chunk held.
    assertThat(result.getQueryRowCounts().keySet())
        .containsExactlyInAnyOrder(
            "findForPosting",
            "findSps",
            "findHubs",
            "findSapMappings",
            "findExcludedCategorySps",
            "findSeries",
            "findAccountCodes");
  }

  @Test
  void anEmptyChunkTouchesNothing() {
    CinntraPassbookChunkResult result =
        service.syncChunk(CinntraPassbookSyncKind.COMMISSION, List.of(), ProgressReporter.noOp());

    assertThat(result.getSucceeded() + result.getFailed() + result.getSkipped()).isZero();
    verify(writeRepository, never()).findForPosting(anyCollection());
  }

  @Test
  void journalSelectionReadsTheTileEndingAtTheScheduledSlotThenTheRetryWindowBeforeIt() {
    lenient().when(readRepository.findIdsInWindow(any(), any())).thenReturn(List.of(3L, 2L));
    lenient().when(readRepository.findUnprocessedIdsInWindow(any(), any())).thenReturn(List.of(1L));
    // Started 20 seconds late for the 10:30 slot.
    long nominal =
        LocalDateTime.of(2026, 10, 22, 10, 30, 20).atZone(IST).toInstant().toEpochMilli();

    CinntraPassbookSelection selection =
        service.selectPassbookIds(CinntraPassbookSyncKind.JOURNAL, nominal);

    verify(readRepository)
        .findIdsInWindow(
            LocalDateTime.of(2026, 10, 22, 10, 0), LocalDateTime.of(2026, 10, 22, 10, 30));
    verify(readRepository)
        .findUnprocessedIdsInWindow(
            LocalDateTime.of(2026, 10, 22, 8, 30), LocalDateTime.of(2026, 10, 22, 10, 0));
    assertThat(selection.getPassbookIds()).containsExactly(3L, 2L, 1L);
    assertThat(selection.getWindowStart()).isEqualTo("2026-10-22 08:30:00");
    assertThat(selection.getWindowEnd()).isEqualTo("2026-10-22 10:30:00");
  }

  @Test
  void dailySelectionIsYesterdayInIstForTheKindsReasonCode() {
    lenient()
        .when(readRepository.findUnprocessedIdsByReasonCode(any(), any(), any()))
        .thenReturn(List.of());
    // 01:00 IST on the 22nd is still the 21st in UTC: the day must come from IST.
    long nominal = LocalDateTime.of(2026, 10, 22, 1, 0, 30).atZone(IST).toInstant().toEpochMilli();

    CinntraPassbookSelection selection =
        service.selectPassbookIds(CinntraPassbookSyncKind.WALLET_RECHARGE, nominal);

    verify(readRepository)
        .findUnprocessedIdsByReasonCode(
            26, LocalDateTime.of(2026, 10, 21, 0, 0), LocalDateTime.of(2026, 10, 22, 0, 0));
    assertThat(selection.getPassbookIds()).isEmpty();
  }

  // ================================================================
  // Expired SAP session
  // ================================================================

  @Test
  void aRejectedSessionIsRefreshedOnceAndThePostRetriedWithTheNewOne() throws Exception {
    sap.rejectSession("SESSION-1");
    givenLoginStores("SESSION-2");
    entry(80L, 43, "DEBIT", 250, null);

    CinntraPassbookChunkResult result = sync(CinntraPassbookSyncKind.JOURNAL, 80L);

    verify(loginService).loginAndStoreSession();
    assertThat(sap.calls()).hasSize(2);
    assertThat(result.getSucceeded()).isEqualTo(1);
    verify(writeRepository).updateSapProcessed(80L, 1);
    // The rejection itself is not an entry failure, so it leaves no error-log row.
    verify(errorLogRepository, never()).save(any());
  }

  @Test
  void aSessionStillRejectedAfterReLoginFailsTheChunkInsteadOfEveryEntry() {
    sap.rejectSession("SESSION-1");
    givenLoginStores("SESSION-1"); // the login "succeeds" but nothing changes
    entry(81L, 43, "DEBIT", 250, null);
    entry(82L, 43, "DEBIT", 250, null);

    assertThatThrownBy(() -> sync(CinntraPassbookSyncKind.JOURNAL, 81L, 82L))
        .isInstanceOf(CinntraPassbookSapSessionException.class);

    // Stopped at the first entry: the second was never sent, and nothing was marked failed.
    assertThat(sap.calls()).hasSize(1);
    verify(writeRepository, never()).updateSapProcessed(anyLong(), anyInt());
    verify(errorLogRepository, never()).save(any());
  }

  @Test
  void concurrentChunksReuseASessionAnotherChunkAlreadyRefreshed() {
    sap.rejectSession("SESSION-1");
    // Another chunk logged in between this one's read and its refresh.
    lenient().when(sessionRepository.findSessionIdOnWriter(1)).thenReturn(Optional.of("SESSION-3"));
    entry(83L, 43, "DEBIT", 250, null);

    CinntraPassbookChunkResult result = sync(CinntraPassbookSyncKind.JOURNAL, 83L);

    verify(loginService, never()).loginAndStoreSession();
    assertThat(result.getSucceeded()).isEqualTo(1);
  }

  @Test
  void aMissingSessionRowFailsTheEntryWithoutALoginOrACallAsInTracking() {
    lenient().when(sessionRepository.findById(1)).thenReturn(Optional.empty());
    entry(84L, 11, "DEBIT", 1180, null);

    CinntraPassbookChunkResult result = sync(CinntraPassbookSyncKind.COMMISSION, 84L);

    verify(loginService, never()).loginAndStoreSession();
    assertThat(sap.calls()).isEmpty();
    assertThat(result.getFailed()).isEqualTo(1);
  }

  @Test
  void onlyA401TriggersARefreshNotAnInvalidSessionBodyOnAnotherStatus() {
    // B1's "Invalid session" body, but on a 400: an ordinary entry failure, as in tracking.
    sap.respondTo("/VendorPayments", 400, SapStubServer.INVALID_SESSION);
    entry(86L, 43, "DEBIT", 250, null);

    CinntraPassbookChunkResult result = sync(CinntraPassbookSyncKind.JOURNAL, 86L);

    verify(loginService, never()).loginAndStoreSession();
    assertThat(sap.calls()).hasSize(1);
    assertThat(result.getFailed()).isEqualTo(1);
    verify(writeRepository).updateSapProcessed(86L, 0);
    verify(errorLogRepository).save(any());
  }

  @Test
  void aForbiddenResponseIsAnOrdinaryFailureToo() {
    sap.respondTo("/JournalEntries", 403, "{\"error\":{\"message\":{\"value\":\"Forbidden\"}}}");
    accountCodes.add(accountCode(33, "52000033"));
    entry(87L, 33, "CREDIT", 75, null);

    CinntraPassbookChunkResult result = sync(CinntraPassbookSyncKind.JOURNAL, 87L);

    verify(loginService, never()).loginAndStoreSession();
    assertThat(result.getFailed()).isEqualTo(1);
  }

  @Test
  void aSessionLostBetweenTheTwoReason40PostsLeavesTheEntryForTheRetryWindow() {
    accountCodes.add(accountCode(40, "41000040"));
    entry(85L, 40, "DEBIT", 90, null);
    // The material post succeeds on SESSION-1; SAP then expires it before the general entry.
    sap.rejectSessionAfter("SESSION-1", 1);
    givenLoginStores("SESSION-1");

    assertThatThrownBy(() -> sync(CinntraPassbookSyncKind.JOURNAL, 85L))
        .isInstanceOf(CinntraPassbookSapSessionException.class);

    verify(writeRepository).updateSapProcessed(85L, 1);
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(writeRepository);
    order.verify(writeRepository).updateSapProcessed(85L, 1);
    order.verify(writeRepository).updateSapProcessed(85L, 0);
  }

  /** The login writes {@code sessionId}, which the writer-side read then returns. */
  private void givenLoginStores(String sessionId) {
    lenient().when(sessionRepository.findSessionIdOnWriter(1)).thenReturn(Optional.of("SESSION-1"));
    doAnswer(
            inv -> {
              lenient()
                  .when(sessionRepository.findSessionIdOnWriter(1))
                  .thenReturn(Optional.of(sessionId));
              return null;
            })
        .when(loginService)
        .loginAndStoreSession();
  }

  // ================================================================
  // Fixtures
  // ================================================================

  private CinntraPassbookChunkResult sync(CinntraPassbookSyncKind kind, Long... ids) {
    return service.syncChunk(kind, List.of(ids), ProgressReporter.noOp());
  }

  private JsonNode body(String pathSuffix) throws Exception {
    return MAPPER.readTree(sap.callTo(pathSuffix).body());
  }

  private List<JsonNode> bodies(String pathSuffix) throws Exception {
    List<JsonNode> out = new ArrayList<>();
    for (SapStubServer.Call call : sap.calls()) {
      if (call.path().endsWith(pathSuffix)) {
        out.add(MAPPER.readTree(call.body()));
      }
    }
    return out;
  }

  private CinntraPassbookSapDocEntry savedDocEntry() {
    ArgumentCaptor<CinntraPassbookSapDocEntry> captor =
        ArgumentCaptor.forClass(CinntraPassbookSapDocEntry.class);
    verify(docEntryRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
    return captor.getValue();
  }

  private CinntraPassbookEntryRow entry(
      Long id, int reasonCode, String actionType, int amount, String comment) {
    CinntraPassbookEntryRow e = new CinntraPassbookEntryRow();
    e.setId(id);
    e.setSpid(SP_ID);
    e.setDate(ENTRY_DATE);
    e.setAmount(amount);
    e.setActiontype(actionType);
    e.setReasonCode(reasonCode);
    e.setComment(comment);
    e.setOrderId(ORDER_ID);
    passbook.put(id, e);
    return e;
  }

  private static CinntraPassbookSpRow sp(int id, String name) {
    CinntraPassbookSpRow sp = new CinntraPassbookSpRow();
    sp.setId(id);
    sp.setName(name);
    sp.setHubId(HUB_ID);
    return sp;
  }

  private static CinntraPassbookHubRow hub() {
    CinntraPassbookHubRow hub = new CinntraPassbookHubRow();
    hub.setId(HUB_ID);
    hub.setHub("DELHI");
    hub.setState("Delhi");
    hub.setIsFranchise(false);
    hub.setSapWhsCode("DL-DL-1");
    hub.setSapBplId(1);
    hub.setCgst(9.0f);
    hub.setSgst(9.0f);
    return hub;
  }

  private static CinntraPassbookSapMappingRow mapping(int spId, int typeCode, String sapId) {
    CinntraPassbookSapMappingRow m = new CinntraPassbookSapMappingRow();
    m.setId(typeCode);
    m.setSpId(spId);
    m.setTypeCode(typeCode);
    m.setSapId(sapId);
    return m;
  }

  private static CinntraPassbookAccountCodeRow accountCode(int reasonCode, String code) {
    CinntraPassbookAccountCodeRow a = new CinntraPassbookAccountCodeRow();
    a.setReasonCode(reasonCode);
    a.setAccountCode(code);
    return a;
  }

  /**
   * Every repository answers from the fixture collections above, so a test edits data, not stubs.
   */
  private void wire() {
    CinntraPassbookSapSeriesRow series = new CinntraPassbookSapSeriesRow();
    series.setId(1);
    series.setSapWhsCode("DL-DL-1");
    series.setSalesOrderSeries(11);
    series.setArInvoiceSeries(22);
    series.setIncomingPaymentSeries(33);
    series.setOutgoingPaymentSeries(44);
    CinntraPassbookSapSession session = new CinntraPassbookSapSession();
    session.setId(1);
    session.setSessionId("SESSION-1");

    lenient().when(sessionRepository.findById(1)).thenReturn(Optional.of(session));
    lenient()
        .when(writeRepository.findForPosting(anyCollection()))
        .thenAnswer(
            inv ->
                inv.<Collection<Long>>getArgument(0).stream()
                    .map(passbook::get)
                    .filter(e -> e != null)
                    .collect(Collectors.toList()));
    lenient()
        .when(writeRepository.findPostedKeys(anyCollection(), any()))
        .thenAnswer(
            inv ->
                inv.<Collection<Integer>>getArgument(0).stream()
                    .filter(postedKeys::contains)
                    .collect(Collectors.toList()));
    lenient()
        .when(readRepository.findSps(anyCollection()))
        .thenAnswer(
            inv ->
                inv.<Collection<Integer>>getArgument(0).stream()
                    .map(sps::get)
                    .filter(s -> s != null)
                    .collect(Collectors.toList()));
    lenient()
        .when(readRepository.findHubs(anyCollection()))
        .thenAnswer(
            inv ->
                inv.<Collection<Integer>>getArgument(0).stream()
                    .map(hubs::get)
                    .filter(h -> h != null)
                    .collect(Collectors.toList()));
    lenient()
        .when(readRepository.findSapMappings(anyCollection(), anyCollection()))
        .thenAnswer(
            inv -> {
              Collection<Integer> spIds = inv.getArgument(0);
              Collection<Integer> types = inv.getArgument(1);
              return mappings.stream()
                  .filter(m -> spIds.contains(m.getSpId()) && types.contains(m.getTypeCode()))
                  .collect(Collectors.toList());
            });
    lenient()
        .when(readRepository.findSpIdsWorkingInCategories(anyCollection(), anyCollection()))
        .thenAnswer(
            inv ->
                inv.<Collection<Integer>>getArgument(0).stream()
                    .filter(excludedCategorySps::contains)
                    .collect(Collectors.toList()));
    lenient().when(readRepository.findSeries(anyCollection())).thenReturn(List.of(series));
    lenient()
        .when(readRepository.findAccountCodes(anyCollection()))
        .thenAnswer(
            inv ->
                accountCodes.stream()
                    .filter(
                        a -> inv.<Collection<Integer>>getArgument(0).contains(a.getReasonCode()))
                    .collect(Collectors.toList()));
    lenient()
        .when(readRepository.findOrderCostingCodes(anyCollection()))
        .thenAnswer(
            inv ->
                inv.<Collection<Long>>getArgument(0).stream()
                    .filter(orderCosting::containsKey)
                    .map(id -> costing(id, orderCosting.get(id)))
                    .collect(Collectors.toList()));
    lenient().when(readRepository.findSpAddresses(eq(SP_ID))).thenReturn(List.of());
  }

  private static CinntraPassbookOrderCostingProjection costing(Long orderId, String code) {
    return new CinntraPassbookOrderCostingProjection() {
      @Override
      public Long getOrderId() {
        return orderId;
      }

      @Override
      public String getCostingCode() {
        return code;
      }
    };
  }
}
