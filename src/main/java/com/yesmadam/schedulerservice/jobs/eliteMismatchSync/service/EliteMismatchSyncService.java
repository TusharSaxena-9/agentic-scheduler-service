package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yesmadam.schedulerservice.common.progress.ProgressReporter;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncAddress;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncBpAddress;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncChunkResult;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncBpFiscalTaxId;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncContactEmployee;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncDocumentLine;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncOrder;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncPaymentInvoice;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncPaymentRequestCopy;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncPurchaseInvoiceRequest;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncRunContext;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncSapMapping;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncSapRequest;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncSapRequestResult;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncSapSeries;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncSapSession;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncServiceHub;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncSubscription;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncUser;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncYmElite;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.SapDocEntries;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.SapErrorLog;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository.EliteMismatchSyncAddressRepository;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository.EliteMismatchSyncOrderRepository;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository.EliteMismatchSyncSapMappingRepository;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository.EliteMismatchSyncSapSeriesRepository;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository.EliteMismatchSyncSapSessionRepository;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository.EliteMismatchSyncServiceHubRepository;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository.EliteMismatchSyncSubscriptionRepository;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository.EliteMismatchSyncUserRepository;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository.EliteMismatchSyncYmEliteRepository;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository.SapDocEntriesRepository;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository.SapErrorLogRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Ports tracking's {@code EliteMismatch#getEliteMismatch} (and the {@code sendSapRequest}/{@code
 * createSapUser}/builder helpers it delegates to) — for each YM-Elite subscription not yet
 * posted to SAP, posts a customer Invoice for the elite-membership fee, then an IncomingPayment
 * against it.
 *
 * <p>Deliberate deviations from the original, flagged here and in the migration report:
 *
 * <ol>
 *   <li><b>Cinntra credentials are never persisted or logged.</b> Same issue as {@code
 *       pendingSpOrderSync} — tracking's {@code Utilities#sendJsonRequestForResult} (used here
 *       via the customer-flavored {@code sendJsonRequestWithAddressRetry}, which the shared
 *       Javadoc in {@code Utilities} names {@code EliteMismatch} as the reference caller for)
 *       builds a curl command embedding the live Key/userId headers, logs it, and persists it
 *       into {@code tbl_sap_error_log.requestJson} on every failure. This port logs and persists
 *       the plain JSON request body only.
 *   <li><b>Per-subscription success/failure is now reported accurately.</b> Tracking's {@code
 *       sendSapRequest} wraps its <i>entire</i> body — address/hub resolution, cardCode
 *       resolution, invoice POST, payment POST — in a single catch-all that only logs (the
 *       literal string {@code "context"}, not even an orderId) and returns normally. It never
 *       signals failure back to its caller. So {@code getEliteMismatch}'s {@code successCount}
 *       is incremented for <i>every</i> subscription that reaches the call — including ones
 *       where the address was null, the hub was unresolvable, or the SAP POST itself failed —
 *       making the original's reported "Success: X, Failed: Y" essentially meaningless for
 *       anything that goes wrong inside {@code sendSapRequest} itself. This port's equivalent
 *       method returns a {@code boolean}, so the sync/failure counts (surfaced only via logs,
 *       since there is no HTTP response to return from a Temporal workflow) reflect what
 *       actually happened. The underlying per-subscription try/catch <i>isolation</i> — one
 *       bad subscription not blocking the rest — is unchanged; only the accounting is fixed.
 * </ol>
 *
 * <p><b>Everything else is preserved exactly, including several notable original bugs/quirks
 * that are not fixed here</b>:
 *
 * <ul>
 *   <li><b>A customer with no default address is invoiced from the default hub.</b> As in
 *       tracking (e2ff20a), the eligibility query {@code LEFT JOIN}s address/hub, and {@code
 *       sendSapRequest} falls back to hub id 5 (UP - Noida Main) when no default address
 *       exists. A newly created BusinessPartner then carries only the hub name + state
 *       (street/block/city blank, zip {@code 0}); the address-retry PATCH is skipped for such
 *       customers since there is no address to patch with.
 *   <li><b>The elite-fee map and YmElite fallback are only correct for the first customer in
 *       the batch.</b> {@code getOrdersWithEliteFees} and the {@code YmElite} lookup are both
 *       called <i>once</i>, keyed off {@code customerSubscriptionList.get(0)} — the first
 *       subscription in the whole "not yet synced" batch — and the resulting map/fallback are
 *       then reused for <i>every</i> subscription in the loop, regardless of which customer or
 *       plan they actually belong to. In practice this means only subscription #0's own
 *       customer's elite-fee orders ever populate the map (other customers' subscription ids
 *       simply miss and fall through to {@code discounted_fee}, or to subscription #0's {@code
 *       YmElite} plan amount as a last resort — which may not even be the right plan for that
 *       subscription). This looks like an unintended bug in the original, but it is reproduced
 *       exactly rather than fixed, since fixing it would change which fee amount gets charged.
 *   <li>{@code createSapUser}'s underlying HTTP call ({@code Utilities#sendJsonRequest2}) wraps
 *       an already-synchronous blocking call in a {@code CompletableFuture} that is always
 *       already-completed by the time it's returned — this port calls the equivalent method
 *       synchronously and returns the result directly, the same simplification already applied
 *       in {@code syncCancellationFeeToSap}/{@code productPurchaseSync}.
 *   <li>{@code buildCustomerRequest} hardcodes a placeholder phone number ({@code
 *       "9999999890"}) and email ({@code "test@gmail.com"}) for every customer's SAP
 *       BusinessPartner record, and concatenates {@code cardName} with no space before
 *       "Customer" (e.g. {@code "123-JohnCustomer A/c"}).
 *   <li><b>Two different, inconsistent state-abbreviation lookups.</b> {@code
 *       getStateAbbreviation} (used when first building the BusinessPartner) falls back to
 *       {@code "DL"} for an unmatched/null state; the address-retry PATCH path's {@code
 *       applyStateAndAddressType} (a different method, shared with the other Cinntra jobs) falls
 *       back to {@code ""} for the same situation. Both are reproduced, each on its own original
 *       call site.
 *   <li><b>Two different truncate-to-100-chars helpers with different null handling.</b> {@code
 *       limitTo100Chars} (used building the initial customer request) returns {@code null} for
 *       a {@code null} input; the address-retry PATCH path's {@code truncateTo100Chars} returns
 *       {@code ""}. Both reproduced on their own original call sites.
 *   <li>{@code buildInvoiceRequest} null-checks {@code seriesCode} before using it (falls back
 *       to {@code Series = 0} if null); {@code buildPaymentRequest}, a few lines later in the
 *       same flow, does <i>not</i> null-check {@code seriesCode} before calling {@code
 *       .getIncomingPaymentSeries()} — an inconsistency in the original, reproduced exactly.
 *   <li>{@code buildPaymentRequest}'s {@code BPLID} is unboxed directly from {@code
 *       hub.getSapBplId()} with no {@code != null ? : 0} fallback, unlike {@code
 *       buildInvoiceRequest}'s guarded version of the same value a few lines earlier.
 *   <li>{@code createSapUser} passes {@code orderId = null} to the BusinessPartner-creation call
 *       — {@code SapErrorLog}/{@code SapDocEntries} rows from that specific call always have a
 *       null {@code orderId}, unlike every other SAP call this job makes.
 *   <li>{@code user}/{@code userPrincipal} are two separate re-fetches of the exact same {@code
 *       User} row by the exact same id — a redundant lookup, reproduced as-is (both are fetched,
 *       neither reused for the other) since {@code createSapUser}'s original signature takes
 *       both parameters.
 * </ul>
 */
@Slf4j
@Service
public class EliteMismatchSyncService {

  /** Business timezone the "yesterday" window is anchored to — matches tracking's IST runtime. */
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private static final DateTimeFormatter DOC_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  private static final Integer SAP_MAPPING_TYPE_USER = 110;
  private static final Integer REQUEST_TYPE_CREATE_USER = 11;
  private static final Integer REQUEST_TYPE_ELITE_INVOICE = 22;
  private static final Integer REQUEST_TYPE_ELITE_PAYMENT = 23;
  private static final Integer SAP_SESSION_ID = 1;
  private static final String ELITE_FEE_ITEM_CODE = "SER000001";

  /** Invoicing hub for customers with no default address: UP - Noida Main. */
  private static final int DEFAULT_INVOICE_HUB_ID = 5;

  private static final int SAP_CONNECT_TIMEOUT_MS = 10_000;
  private static final int SAP_READ_TIMEOUT_MS = 60_000;

  /** SAP BusinessPartner numbering series for newly created elite customers. */
  private static final Integer CUSTOMER_BP_SERIES = 1757;

  private static final Map<String, String> STATE_ABBREVIATIONS_FOR_NEW_CUSTOMER = Map.ofEntries(
      Map.entry("andhra pradesh", "AP"), Map.entry("arunachal pradesh", "AR"), Map.entry("assam", "AS"),
      Map.entry("bihar", "BH"), Map.entry("chhattisgarh", "CT"), Map.entry("goa", "GA"),
      Map.entry("gujarat", "GJ"), Map.entry("haryana", "HR"), Map.entry("himachal pradesh", "HP"),
      Map.entry("jharkhand", "JH"), Map.entry("karnataka", "KT"), Map.entry("madhya pradesh", "MP"),
      Map.entry("maharashtra", "MH"), Map.entry("manipur", "MN"), Map.entry("meghalaya", "ME"),
      Map.entry("mizoram", "MI"), Map.entry("nagaland", "NL"), Map.entry("odisha", "OR"),
      Map.entry("punjab", "PB"), Map.entry("rajasthan", "RJ"), Map.entry("sikkim", "SK"),
      Map.entry("tamil nadu", "TN"), Map.entry("telangana", "TL"), Map.entry("tripura", "TR"),
      Map.entry("uttar pradesh", "UP"), Map.entry("uttarakhand", "UK"), Map.entry("west bengal", "WB"),
      Map.entry("delhi", "DL"), Map.entry("dadra and nagar haveli", "DN"), Map.entry("daman and diu", "DD"),
      Map.entry("chandigarh", "CH"), Map.entry("andaman and nicobar islands", "AN"),
      Map.entry("jammu and kashmir", "JK"), Map.entry("ladakh", "LA"), Map.entry("lakshadweep islands", "LD"),
      Map.entry("pondicherry", "PY"));

  /** Same 36-entry list as above, used only by the address-retry PATCH path — kept separate because its fallback differs (see class Javadoc). */
  private static final Map<String, String> STATE_ABBREVIATIONS_FOR_ADDRESS_RETRY = STATE_ABBREVIATIONS_FOR_NEW_CUSTOMER;

  @Value("${com.cinntra.url}")
  private String cinntraBaseUrl;

  @Value("${com.cinntra.key}")
  private String cinntraKey;

  @Value("${com.cinntra.userId}")
  private String cinntraUserId;

  private final EliteMismatchSyncSubscriptionRepository subscriptionRepository;
  private final EliteMismatchSyncOrderRepository orderRepository;
  private final EliteMismatchSyncYmEliteRepository ymEliteRepository;
  private final EliteMismatchSyncUserRepository userRepository;
  private final EliteMismatchSyncAddressRepository addressRepository;
  private final EliteMismatchSyncServiceHubRepository serviceHubRepository;
  private final EliteMismatchSyncSapMappingRepository sapMappingRepository;
  private final EliteMismatchSyncSapSeriesRepository sapSeriesRepository;
  private final EliteMismatchSyncSapSessionRepository sapSessionRepository;
  private final SapErrorLogRepository sapErrorLogRepository;
  private final SapDocEntriesRepository sapDocEntriesRepository;

  public EliteMismatchSyncService(
      EliteMismatchSyncSubscriptionRepository subscriptionRepository,
      EliteMismatchSyncOrderRepository orderRepository,
      EliteMismatchSyncYmEliteRepository ymEliteRepository,
      EliteMismatchSyncUserRepository userRepository,
      EliteMismatchSyncAddressRepository addressRepository,
      EliteMismatchSyncServiceHubRepository serviceHubRepository,
      EliteMismatchSyncSapMappingRepository sapMappingRepository,
      EliteMismatchSyncSapSeriesRepository sapSeriesRepository,
      EliteMismatchSyncSapSessionRepository sapSessionRepository,
      SapErrorLogRepository sapErrorLogRepository,
      SapDocEntriesRepository sapDocEntriesRepository) {
    this.subscriptionRepository = subscriptionRepository;
    this.orderRepository = orderRepository;
    this.ymEliteRepository = ymEliteRepository;
    this.userRepository = userRepository;
    this.addressRepository = addressRepository;
    this.serviceHubRepository = serviceHubRepository;
    this.sapMappingRepository = sapMappingRepository;
    this.sapSeriesRepository = sapSeriesRepository;
    this.sapSessionRepository = sapSessionRepository;
    this.sapErrorLogRepository = sapErrorLogRepository;
    this.sapDocEntriesRepository = sapDocEntriesRepository;
  }

  /**
   * Mirrors tracking's {@code getSubscriptionOrderIdsNotInSapDoc()} call and the part of {@code
   * getEliteMismatch} that runs before its loop: load each subscription, drop missing ones, and
   * resolve the fee map and plan amount once from the first subscription.
   *
   * <p>Read-only, so it goes to the replica (CLAUDE.md rule 13). A few seconds of lag cannot matter
   * here: the window is yesterday.
   */
  @Transactional(readOnly = true)
  public EliteMismatchSyncRunContext prepareRun() {
    LocalDate yesterday = LocalDate.now(IST).minusDays(1);
    LocalDateTime startOfDay = yesterday.atStartOfDay();
    LocalDateTime endOfDay = yesterday.plusDays(1).atStartOfDay();
    List<Integer> ids = subscriptionRepository.findSubscriptionIdsNotInSapDoc(startOfDay, endOfDay);
    return buildRunContext(ids == null ? List.of() : ids);
  }

  /** The pre-loop half of tracking's {@code getEliteMismatch}, for a given list of subscription ids. */
  public EliteMismatchSyncRunContext buildRunContext(List<Integer> subscriptionIds) {
    if (ObjectUtils.isEmpty(subscriptionIds)) {
      log.info("No orders to sync.");
      return new EliteMismatchSyncRunContext(List.of(), Map.of(), null);
    }

    List<EliteMismatchSyncSubscription> customerSubscriptionList = new ArrayList<>();
    // One IN (...) query on the primary key instead of tracking's findOne per id; the list is one
    // day's subscriptions, so the IN list stays small (CLAUDE.md rule 15). findAllById returns rows
    // in no particular order, so they are put back in the selection query's order (createdAt DESC):
    // get(0) below must be the same subscription tracking would have picked. Missing rows are
    // dropped, as tracking's null check did.
    Map<Integer, EliteMismatchSyncSubscription> byId = new HashMap<>();
    for (EliteMismatchSyncSubscription cs : subscriptionRepository.findAllById(subscriptionIds)) {
      byId.put(cs.getId(), cs);
    }
    for (Integer id : subscriptionIds) {
      EliteMismatchSyncSubscription cs = byId.get(id);
      if (cs != null) {
        customerSubscriptionList.add(cs);
      }
    }

    if (customerSubscriptionList.isEmpty()) {
      log.info("No valid subscriptions found.");
      return new EliteMismatchSyncRunContext(List.of(), Map.of(), null);
    }

    // Preserved exactly from the original: both the elite-fee order map and the YmElite fallback
    // are resolved ONCE, from customerSubscriptionList.get(0) only, then reused for every
    // subscription in every chunk — see class Javadoc for why this is likely an unintended
    // bug that is nonetheless reproduced faithfully.
    List<EliteMismatchSyncOrder> orderWithElitePurchase =
        orderRepository.findByUseridAndEliteFeesIsNotNull(customerSubscriptionList.get(0).getCustomerId().intValue());

    Map<Integer, Integer> subscriptionIdEliteFeeMap = orderWithElitePurchase.stream()
        .filter(order -> order.getEliteFees() != null)
        .sorted(Comparator.comparing(EliteMismatchSyncOrder::getCreatedAt).reversed())
        .collect(java.util.stream.Collectors.toMap(
            EliteMismatchSyncOrder::getCustomerSubscriptionId, EliteMismatchSyncOrder::getEliteFees, (existing, replacement) -> existing));

    Integer fallbackPlanAmount = ymEliteRepository
        .findById(customerSubscriptionList.get(0).getSubscriptionId())
        .map(EliteMismatchSyncYmElite::getAmount)
        .orElse(null);

    log.info("{}", subscriptionIdEliteFeeMap);

    List<Integer> validIds = new ArrayList<>();
    for (EliteMismatchSyncSubscription cs : customerSubscriptionList) {
      validIds.add(cs.getId());
    }
    return new EliteMismatchSyncRunContext(validIds, new HashMap<>(subscriptionIdEliteFeeMap), fallbackPlanAmount);
  }

  /**
   * The loop of tracking's {@code getEliteMismatch}, over one chunk.
   *
   * <p>Retry-safe (CLAUDE.md rule 14): a subscription that already has a {@code
   * tbl_sap_doc_entries} row is skipped. That is the same test tracking's selection query applies,
   * so a retried chunk does not invoice twice what an earlier attempt already posted. Reports
   * progress once per subscription so the activity can heartbeat (rule 10).
   *
   * <p>Not transactional: each subscription is a sequence of SAP HTTP calls, and a transaction
   * around them would pin a connection for the length of SAP's response time (rule 13).
   */
  public EliteMismatchSyncChunkResult syncChunk(
      List<Integer> subscriptionIds,
      Map<Integer, Integer> eliteFeeBySubscriptionId,
      Integer fallbackPlanAmount,
      ProgressReporter progress) {
    EliteMismatchSyncChunkResult result = new EliteMismatchSyncChunkResult();
    Map<Integer, Integer> subscriptionIdEliteFeeMap =
        eliteFeeBySubscriptionId == null ? Map.of() : eliteFeeBySubscriptionId;
    int completed = 0;

    for (Integer subscriptionId : subscriptionIds) {
      if (sapDocEntriesRepository.existsByOrderId(subscriptionId)) {
        log.info("Subscription {} already has a SAP doc entry, skipping", subscriptionId);
        result.setAlreadySynced(result.getAlreadySynced() + 1);
        progress.report(++completed);
        continue;
      }

      EliteMismatchSyncSubscription customerSubscription = subscriptionRepository.findById(subscriptionId).orElse(null);
      if (customerSubscription == null) {
        result.setNotFound(result.getNotFound() + 1);
        progress.report(++completed);
        continue;
      }

      try {
        EliteMismatchSyncUser userPrincipal = userRepository.findById(customerSubscription.getCustomerId()).orElse(null);

        Integer amount;
        Integer eliteFeeFromMap = subscriptionIdEliteFeeMap.getOrDefault(customerSubscription.getId(), null);

        if (eliteFeeFromMap != null) {
          log.info("[getEliteMismatch] Using elite fee from order map for subscription ID: {} | Amount: {}",
              customerSubscription.getId(), eliteFeeFromMap);
          amount = eliteFeeFromMap;
        } else if (customerSubscription.getDiscountedFee() != null) {
          log.info("[getEliteMismatch] Order map fee not found. Using discounted fees from CustomerSubscription ID: {} | Amount: {}",
              customerSubscription.getId(), customerSubscription.getDiscountedFee());
          amount = customerSubscription.getDiscountedFee();
        } else {
          log.warn("[getEliteMismatch] No fee found in order map or discounted fees. Falling back to YmElite amount for subscription ID: {} | Amount: {}",
              customerSubscription.getId(), fallbackPlanAmount);
          amount = fallbackPlanAmount;
        }

        boolean synced = sendSapRequest(
            customerSubscription.getId() != null ? customerSubscription.getId() : 0,
            userPrincipal,
            amount.longValue(),
            ELITE_FEE_ITEM_CODE,
            customerSubscription.getTxnid() != null ? customerSubscription.getTxnid() : "");
        if (synced) {
          result.setSucceeded(result.getSucceeded() + 1);
        } else {
          result.setFailed(result.getFailed() + 1);
        }
      } catch (Exception e) {
        result.setFailed(result.getFailed() + 1);
        log.error("Failed to process order ID: {}, Error: {}", customerSubscription.getId(), e.getMessage(), e);
      }
      progress.report(++completed);
    }

    log.info("Data synced. Success: {}, Failed: {}, Already synced: {}, Not found: {}",
        result.getSucceeded(), result.getFailed(), result.getAlreadySynced(), result.getNotFound());
    return result;
  }

  /** Whole run in one call, outside Temporal — tracking's {@code getEliteMismatch} for a given id list. */
  public EliteMismatchSyncChunkResult syncEliteMismatches(List<Integer> subscriptionIds) {
    EliteMismatchSyncRunContext context = buildRunContext(subscriptionIds);
    return syncChunk(
        context.getSubscriptionIds(),
        context.getEliteFeeBySubscriptionId(),
        context.getFallbackPlanAmount(),
        ProgressReporter.noOp());
  }

  /**
   * Mirrors tracking's {@code sendSapRequest}. Unlike the original (which swallows every
   * exception here and never signals failure to its caller — see class Javadoc), this returns
   * {@code true}/{@code false} so the caller's success/failure accounting is accurate.
   */
  private boolean sendSapRequest(Integer orderId, EliteMismatchSyncUser userPrincipal, Long txnAmount, String itemCode, String txnId) {
    try {
      EliteMismatchSyncAddress address =
          addressRepository.findFirstByUserIdAndIsDefault(userPrincipal.getUserId(), true).orElse(null);
      EliteMismatchSyncServiceHub hub;
      if (address == null) {
        log.warn("No default address found for userId: {}, orderId: {}. Invoicing from default hub id {} (UP - Noida Main).",
            userPrincipal.getUserId(), orderId, DEFAULT_INVOICE_HUB_ID);
        hub = serviceHubRepository.findById(DEFAULT_INVOICE_HUB_ID).orElse(null);
      } else {
        hub = serviceHubRepository.findById(address.getHubId()).orElse(null);
      }

      EliteMismatchSyncSapMapping sapMap =
          sapMappingRepository.findFirstBySpIdAndTypeCode(userPrincipal.getUserId().intValue(), SAP_MAPPING_TYPE_USER).orElse(null);
      EliteMismatchSyncUser user = userRepository.findById(userPrincipal.getUserId()).orElse(null);

      String yesterday = LocalDate.now(IST).minusDays(1).format(DOC_DATE_FORMATTER);

      String cardCode = (sapMap != null) ? sapMap.getSapId() : createSapUser(userPrincipal, user, address, hub);
      if (cardCode == null) {
        log.info("cardCode null");
        cardCode = "";
      }

      log.info("EliteMismatch HUB {}", hub);
      log.info("EliteMismatch sapWhsCode {}", hub.getSapWhsCode());
      EliteMismatchSyncSapSeries seriesCode = sapSeriesRepository.findBySapWhsCode(hub.getSapWhsCode()).orElse(null);
      log.info("EliteMismatch seriesCode --> {}", seriesCode);

      EliteMismatchSyncDocumentLine documentLine = buildDocumentLine(itemCode, txnAmount, hub);
      List<EliteMismatchSyncDocumentLine> documentLines = List.of(documentLine);

      EliteMismatchSyncPurchaseInvoiceRequest invoiceRequest =
          buildInvoiceRequest(orderId, cardCode, yesterday, hub, documentLines, seriesCode);

      EliteMismatchSyncSapRequestResult invoiceResult = sendJsonRequestWithAddressRetry(
          cinntraBaseUrl + "Invoices", invoiceRequest, orderId.longValue(), REQUEST_TYPE_ELITE_INVOICE, null, address, hub, cardCode);

      if (!invoiceResult.isSuccess() || invoiceResult.getCardCode() == null) {
        log.error("Response from SAP is null or missing cardCode: {}", invoiceResult.getErrorMessage());
        throw new RuntimeException("Invalid SAP response");
      }

      String docEntry = invoiceResult.getDocEntry();
      log.info("EliteMismatch CardCode: {}", cardCode);
      log.info("EliteMismatch DocEntry: {}", docEntry);

      EliteMismatchSyncPaymentRequestCopy paymentRequest =
          buildPaymentRequest(yesterday, cardCode, txnAmount, docEntry, seriesCode, hub, txnId);

      postFireAndForget(cinntraBaseUrl + "IncomingPayments", paymentRequest, orderId.longValue(), REQUEST_TYPE_ELITE_PAYMENT, null);

      return true;
    } catch (Exception e) {
      log.error("Failed to sync elite mismatch for orderId={}: {}", orderId, e.getMessage(), e);
      return false;
    }
  }

  /**
   * Mirrors tracking's {@code createSapUser}. Simplified to a synchronous call (no {@code
   * CompletableFuture} wrapper around an already-synchronous HTTP call) — see class Javadoc.
   */
  private String createSapUser(EliteMismatchSyncUser userPrincipal, EliteMismatchSyncUser user, EliteMismatchSyncAddress address, EliteMismatchSyncServiceHub hub) {
    try {
      EliteMismatchSyncSapRequest customerRequest = buildCustomerRequest(user, address, hub);

      String requestUrl = cinntraBaseUrl + "BusinessPartners";
      // orderId is null here, exactly as in tracking — see class Javadoc.
      Map<String, String> responseMap = sendJsonRequestForCardCodeAndDocEntry(requestUrl, customerRequest, null, REQUEST_TYPE_CREATE_USER);

      if (responseMap == null || responseMap.get("cardCode") == null) {
        log.error("Response from SAP is null or missing cardCode");
        throw new RuntimeException("Invalid SAP response");
      }

      String cardCode = responseMap.get("cardCode");
      String docEntry = responseMap.get("docEntry");
      log.info("EliteMismatch create user CardCode: {}", cardCode);
      log.info("EliteMismatch create user DocEntry: {}", docEntry);

      EliteMismatchSyncSapMapping sapMap = new EliteMismatchSyncSapMapping();
      sapMap.setSapId(cardCode != null ? cardCode : "");
      sapMap.setSpId(user.getUserId() != null ? user.getUserId().intValue() : 0);
      sapMap.setType("USER");
      sapMap.setTypeCode(SAP_MAPPING_TYPE_USER);
      sapMappingRepository.save(sapMap);

      return cardCode;
    } catch (Exception e) {
      log.error("Exception occurred in createSapUser(): {}", e.getMessage(), e);
      throw new RuntimeException("Failed to create SAP user", e);
    }
  }

  private EliteMismatchSyncSapRequest buildCustomerRequest(EliteMismatchSyncUser user, EliteMismatchSyncAddress address, EliteMismatchSyncServiceHub hub) {
    EliteMismatchSyncSapRequest request = new EliteMismatchSyncSapRequest();
    try {
      request.setCardCode(user.getUserId() != null ? user.getUserId().toString() : "");
      request.setCardName(user.getUserId() + "-" + user.getName() + "Customer A/c");
      request.setCardType("C");
      request.setGroupCode(109);
      request.setPhone1("9999999890");
      request.setPayTermsGrpCode(-1);
      request.setCurrency("INR");
      request.setContactPerson(user.getName());
      request.setCellular("9999999890");
      request.setEmailAddress("test@gmail.com");
      request.setUseBillToAddrToDetermineTax("Y");
      request.setTaxExemptionLetterNum(null);
      request.setSeries(CUSTOMER_BP_SERIES);

      EliteMismatchSyncContactEmployee contact = new EliteMismatchSyncContactEmployee();
      contact.setName(user.getName());
      contact.setPhone1("9999999890");
      contact.setMobilePhone("9999999890");
      contact.setEmail("test@gmail.com");
      contact.setDateOfBirth(user.getDob() != null ? user.getDob().toString() : "");
      contact.setGender("gt_Undefined");
      contact.setActive("Y");
      request.setContactEmployees(List.of(contact));

      String stateShortForm = getStateAbbreviation(hub.getState());

      EliteMismatchSyncBpAddress billTo = buildNewCustomerBpAddress(address, hub, stateShortForm, "bo_BillTo");
      EliteMismatchSyncBpAddress shipTo = buildNewCustomerBpAddress(address, hub, stateShortForm, "bo_ShipTo");
      request.setBpAddresses(List.of(billTo, shipTo));

      EliteMismatchSyncBpFiscalTaxId taxID = new EliteMismatchSyncBpFiscalTaxId();
      taxID.setTaxId0(null);
      taxID.setTaxId2(null);
      taxID.setTaxId3("");
      taxID.setAddrType("bo_BillTo");
      request.setBpFiscalTaxIDCollection(List.of(taxID));
    } catch (Exception ex) {
      log.error("Error while building SapRequest in buildCustomerRequest(): {}", ex.getMessage(), ex);
      throw new RuntimeException("Failed to build customer request", ex);
    }
    return request;
  }

  private EliteMismatchSyncBpAddress buildNewCustomerBpAddress(EliteMismatchSyncAddress address, EliteMismatchSyncServiceHub hub, String stateShortForm, String addressType) {
    EliteMismatchSyncBpAddress bpAddress = new EliteMismatchSyncBpAddress();
    bpAddress.setAddressName(hub.getHub());
    // No default address (invoiced from the default hub): only the hub name + state go on the BP.
    bpAddress.setStreet(limitTo100Chars(address != null && address.getAddress() != null ? address.getAddress() : ""));
    bpAddress.setBlock(limitTo100Chars(address != null && address.getAddress1() != null ? address.getAddress1() : ""));
    bpAddress.setZipCode(address != null && address.getPincode() != null && !address.getPincode().isBlank()
        ? Integer.valueOf(address.getPincode())
        : 0);
    bpAddress.setCity(address != null && address.getCity() != null ? address.getCity() : "");
    bpAddress.setCounty("INDIA");
    bpAddress.setState(stateShortForm != null ? stateShortForm : "");
    bpAddress.setAddressType(addressType);
    bpAddress.setGstin("");
    bpAddress.setGstType("");
    return bpAddress;
  }

  /** Mirrors tracking's own inline {@code getStateAbbreviation} — falls back to "DL", unlike the address-retry PATCH path's shared lookup (see class Javadoc). */
  private String getStateAbbreviation(String fullStateName) {
    try {
      return fullStateName != null
          ? STATE_ABBREVIATIONS_FOR_NEW_CUSTOMER.getOrDefault(fullStateName.trim().toLowerCase(), "DL")
          : "DL";
    } catch (Exception ex) {
      log.error("Error in getStateAbbreviation for state '{}': {}", fullStateName, ex.getMessage(), ex);
      return "DL";
    }
  }

  private EliteMismatchSyncDocumentLine buildDocumentLine(String itemCode, Long txnAmount, EliteMismatchSyncServiceHub hub) {
    try {
      double baseAmount = txnAmount / 1.18;
      EliteMismatchSyncDocumentLine line = new EliteMismatchSyncDocumentLine();
      line.setItemCode(itemCode);
      line.setQuantity(1.0);
      line.setUnitPrice(baseAmount);
      line.setCurrency("INR");
      line.setDiscountPercent(0.0);
      line.setWarehouseCode(hub.getSapWhsCode() != null ? hub.getSapWhsCode() : "");
      line.setTreeType("iNotATree");
      line.setFreeText("");
      line.setBaseType(-1);
      line.setBaseEntry(null);
      line.setBaseLine(null);

      String state = hub.getState();
      log.info("State --> {}", state);
      String taxcode = null;
      if (hub != null) {
        if (hub.getIgst() != null && hub.getIgst() > 0) {
          taxcode = "IGST@" + hub.getIgst().intValue();
        } else if (hub.getCgst() != null && hub.getSgst() != null && hub.getCgst() > 0 && hub.getSgst() > 0) {
          int totalGst = Math.round(hub.getCgst() + hub.getSgst());
          taxcode = "CG+SG@" + totalGst;
        }
      }
      line.setTaxCode(taxcode);
      return line;
    } catch (Exception ex) {
      log.error("Exception occurred in buildDocumentLine(): {}", ex.getMessage(), ex);
      throw new RuntimeException("Failed to build document line", ex);
    }
  }

  private EliteMismatchSyncPurchaseInvoiceRequest buildInvoiceRequest(
      Integer orderId, String cardCode, String today, EliteMismatchSyncServiceHub hub,
      List<EliteMismatchSyncDocumentLine> documentLines, EliteMismatchSyncSapSeries seriesCode) {
    try {
      EliteMismatchSyncPurchaseInvoiceRequest request = new EliteMismatchSyncPurchaseInvoiceRequest();
      request.setDocType("dDocument_Items");
      request.setDocDate(today);
      request.setDocDueDate(today);
      request.setCardCode(cardCode);
      if (seriesCode != null) {
        request.setSeries(seriesCode.getArInvoiceSeries());
      } else {
        request.setSeries(0);
      }
      request.setTaxDate(today);
      request.setGstTransactionType("gsttrantyp_GSTTaxInvoice");
      request.setBpl_IDAssignedToInvoice(hub.getSapBplId() != null ? hub.getSapBplId() : 0);
      request.setNumAtCard(orderId.toString());
      request.setComments("");
      request.setDocObjectCode("");
      request.setShipToCode(hub.getHub());
      request.setPayToCode(hub.getHub());
      request.setOriginalRefNo(null);
      request.setOriginalRefDate(null);
      request.setUtos("NA");
      request.setDocumentLines(documentLines);
      return request;
    } catch (Exception ex) {
      log.error("Exception in buildInvoiceRequest(): {}", ex.getMessage(), ex);
      throw new RuntimeException("Failed to build invoice request", ex);
    }
  }

  private EliteMismatchSyncPaymentRequestCopy buildPaymentRequest(
      String today, String cardCode, Long txnAmount, String docEntry, EliteMismatchSyncSapSeries seriesCode,
      EliteMismatchSyncServiceHub hub, String txnId) {
    try {
      EliteMismatchSyncPaymentInvoice invoice = new EliteMismatchSyncPaymentInvoice();
      invoice.setDocEntry(docEntry);
      invoice.setSumApplied(txnAmount);
      invoice.setInvoiceType("it_Invoice");

      EliteMismatchSyncPaymentRequestCopy payment = new EliteMismatchSyncPaymentRequestCopy();
      payment.setDocType("rCustomer");
      payment.setDocDate(today);
      payment.setCardCode(cardCode);
      payment.setCashAccount(null);
      payment.setDocCurrency("INR");
      payment.setCashSum(0.0);
      payment.setTransferAccount("14512004");
      payment.setTransferSum(txnAmount);
      payment.setTransferDate(today);
      payment.setTransferReference(null);
      payment.setDocRate(0.0);
      payment.setTaxDate(today);
      // Not null-guarded, unlike buildInvoiceRequest's use of seriesCode above — preserved, see class Javadoc.
      payment.setSeries(seriesCode.getIncomingPaymentSeries());
      payment.setBankCode(null);
      payment.setBankAccount(null);
      payment.setDiscountPercent(0.0);
      payment.setProjectCode(null);
      payment.setIsPayToBank("tNO");
      payment.setDocTypte("rCustomer");
      payment.setDueDate(today);
      payment.setDocObjectCode("24");
      payment.setUti(txnId);
      // Unboxed with no null fallback, unlike buildInvoiceRequest's guarded bpl_IDAssignedToInvoice — preserved, see class Javadoc.
      payment.setBplID(hub.getSapBplId());
      payment.setPaymentInvoices(List.of(invoice));
      return payment;
    } catch (Exception ex) {
      log.error("Exception in buildPaymentRequest(): {}", ex.getMessage(), ex);
      throw new RuntimeException("Failed to build payment request", ex);
    }
  }

  /** Returns {@code null} for a {@code null} input — a different helper from {@link #truncateTo100CharsForAddressRetry}, see class Javadoc. */
  private String limitTo100Chars(String input) {
    return input != null && input.length() > 100 ? input.substring(0, 100) : input;
  }

  /** Returns {@code ""} for a {@code null} input — used only by the address-retry PATCH path, see class Javadoc. */
  private String truncateTo100CharsForAddressRetry(String value) {
    if (value == null) {
      return "";
    }
    return value.length() > 100 ? value.substring(0, 100) : value;
  }

  // ---------------------------------------------------------------------------------------
  // SAP HTTP — mirrors tracking's Utilities#sendJsonRequestForResult / #sendJsonRequestWithAddressRetry (customer-flavored)
  // ---------------------------------------------------------------------------------------

  /**
   * Generic, call-site-agnostic wrapper: send, and on a "no matching record" address failure,
   * PATCH the customer's BusinessPartner address and retry exactly once. Mirrors tracking's
   * customer-flavored {@code Utilities#sendJsonRequestWithAddressRetry} overload.
   */
  private EliteMismatchSyncSapRequestResult sendJsonRequestWithAddressRetry(
      String requestUrl, Object sapRequest, Long orderId, Integer requestType, Integer journalTypeCode,
      EliteMismatchSyncAddress address, EliteMismatchSyncServiceHub serviceHub, String sapCardCode) {
    EliteMismatchSyncSapRequestResult result = sendJsonRequestForResult(requestUrl, sapRequest, orderId, requestType, journalTypeCode);

    if (!shouldRetryWithAddressPatch(result, address, sapCardCode)) {
      return result;
    }

    log.info("[SAP-ADDR-RETRY] '{}' for userId={} cardCode={} — attempting address PATCH + single retry",
        result.getErrorMessage(), address.getUserId(), sapCardCode);

    try {
      patchBusinessPartnerAddress(requestUrl, address, serviceHub, sapCardCode);

      log.info("[SAP-ADDR-RETRY] Retrying original request once for userId={} cardCode={}", address.getUserId(), sapCardCode);
      EliteMismatchSyncSapRequestResult retryResult = sendJsonRequestForResult(requestUrl, sapRequest, orderId, requestType, journalTypeCode);
      if (retryResult.isSuccess()) {
        log.info("[SAP-ADDR-RETRY] Retry succeeded for userId={} cardCode={}", address.getUserId(), sapCardCode);
      } else {
        log.warn("[SAP-ADDR-RETRY] Retry also failed for userId={} cardCode={} — giving up (no further retries): {}",
            address.getUserId(), sapCardCode, retryResult.getErrorMessage());
      }
      return retryResult;
    } catch (Exception e) {
      log.error("[SAP-ADDR-RETRY] Unexpected error during address PATCH/retry for userId=" + address.getUserId() + " cardCode=" + sapCardCode, e);
      return result;
    }
  }

  private boolean shouldRetryWithAddressPatch(EliteMismatchSyncSapRequestResult result, EliteMismatchSyncAddress address, String sapCardCode) {
    return isNoMatchingRecordError(result) && address != null && sapCardCode != null && !sapCardCode.isEmpty();
  }

  private boolean isNoMatchingRecordError(EliteMismatchSyncSapRequestResult result) {
    if (result.isSuccess() || result.getErrorMessage() == null) {
      return false;
    }
    return result.getErrorMessage().toLowerCase().contains("no matching record");
  }

  private void patchBusinessPartnerAddress(String requestUrl, EliteMismatchSyncAddress address, EliteMismatchSyncServiceHub serviceHub, String sapCardCode) throws Exception {
    Map<String, Object> patchPayload = buildBpAddressPatchPayload(address, serviceHub);
    String patchUrl = buildBusinessPartnerPatchUrl(requestUrl, sapCardCode);

    boolean patchOk = sendPatchJsonRequest(patchUrl, patchPayload);
    log.info("[SAP-ADDR-RETRY] Address PATCH for cardCode={} success={}", sapCardCode, patchOk);
  }

  private Map<String, Object> buildBpAddressPatchPayload(EliteMismatchSyncAddress address, EliteMismatchSyncServiceHub serviceHub) {
    EliteMismatchSyncBpAddress billTo = buildAddressRetryBpAddress(address, serviceHub, "bo_BillTo");
    EliteMismatchSyncBpAddress shipTo = buildAddressRetryBpAddress(address, serviceHub, "bo_ShipTo");

    Map<String, Object> requestPayload = new HashMap<>();
    requestPayload.put("BPAddresses", List.of(billTo, shipTo));
    return requestPayload;
  }

  private EliteMismatchSyncBpAddress buildAddressRetryBpAddress(EliteMismatchSyncAddress address, EliteMismatchSyncServiceHub serviceHub, String addressType) {
    EliteMismatchSyncBpAddress bpaddress = new EliteMismatchSyncBpAddress();
    bpaddress.setAddressName(serviceHub != null && serviceHub.getHub() != null ? serviceHub.getHub() : "");
    if (address != null) {
      bpaddress.setStreet(truncateTo100CharsForAddressRetry(address.getAddress()));
      bpaddress.setBlock(truncateTo100CharsForAddressRetry(address.getAddress1()));
      bpaddress.setZipCode(address.getPincode() != null && !address.getPincode().isBlank() ? Integer.valueOf(address.getPincode()) : 0);
      bpaddress.setCity(address.getCity() != null ? address.getCity() : "");
    }
    bpaddress.setCounty("INDIA");
    String fullStateName = serviceHub != null && serviceHub.getState() != null ? serviceHub.getState() : "";
    bpaddress.setState(STATE_ABBREVIATIONS_FOR_ADDRESS_RETRY.getOrDefault(fullStateName.toLowerCase(), ""));
    bpaddress.setAddressType(addressType);
    bpaddress.setGstin("");
    bpaddress.setGstType("");
    return bpaddress;
  }

  private String buildBusinessPartnerPatchUrl(String requestUrl, String sapCardCode) {
    String baseUrl = requestUrl.substring(0, requestUrl.lastIndexOf('/') + 1);
    return baseUrl + "BusinessPartners('" + sapCardCode + "')";
  }

  /** PATCH a SAP BusinessPartner address. Mirrors tracking's {@code Utilities#sendPatchJsonRequest}. */
  private boolean sendPatchJsonRequest(String requestUrl, Object payload) {
    try {
      String jsonRequest = new ObjectMapper().writeValueAsString(payload);
      log.info("[SAP-ADDR-RETRY] PATCH JSON Request: {}", jsonRequest);

      String sessionId = currentSapSessionId();
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(10000)).build();
      HttpRequest httpRequest = HttpRequest.newBuilder()
          .uri(URI.create(requestUrl))
          .timeout(Duration.ofMillis(10000))
          .header("Content-Type", "application/json")
          .header("Key", cinntraKey)
          .header("userId", cinntraUserId)
          .header("Cookie", "B1SESSION=" + sessionId)
          .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonRequest, StandardCharsets.UTF_8))
          .build();

      HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      int responseCode = response.statusCode();
      log.info("[SAP-ADDR-RETRY] PATCH Response Code: {}", responseCode);
      if (responseCode >= 400) {
        String extractedMessage = extractSapErrorMessage(response.body());
        log.error("[SAP-ADDR-RETRY] PATCH failed ({}): {}", responseCode, extractedMessage);
        return false;
      }
      return true;
    } catch (Exception e) {
      log.error("[SAP-ADDR-RETRY] Error while sending PATCH request: {}", e.getMessage(), e);
      return false;
    }
  }

  /** Mirrors tracking's {@code Utilities#sendJsonRequestForResult}. See class Javadoc for the one deviation (no curl-command reproduction). */
  private EliteMismatchSyncSapRequestResult sendJsonRequestForResult(
      String requestUrl, Object sapRequest, Long orderId, Integer requestType, Integer journalTypeCode) {
    EliteMismatchSyncSapRequestResult result = new EliteMismatchSyncSapRequestResult();
    HttpURLConnection conn = null;
    try {
      ObjectMapper objectMapper = new ObjectMapper();
      String jsonRequest = objectMapper.writeValueAsString(sapRequest);
      log.info("JSON Request: {}", jsonRequest);

      String sessionId = currentSapSessionId();
      conn = openSapConnection(new URL(requestUrl));
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setRequestProperty("Key", cinntraKey);
      conn.setRequestProperty("userId", cinntraUserId);
      conn.setRequestProperty("Cookie", "B1SESSION=" + sessionId);
      conn.setDoOutput(true);

      try (OutputStream os = conn.getOutputStream()) {
        byte[] input = jsonRequest.getBytes(StandardCharsets.UTF_8);
        os.write(input, 0, input.length);
      }

      int responseCode = conn.getResponseCode();
      result.setResponseCode(responseCode);

      StringBuilder response = new StringBuilder();
      try (InputStream inputStream = responseCode < 400 ? conn.getInputStream() : conn.getErrorStream();
          BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
        String responseLine;
        while ((responseLine = br.readLine()) != null) {
          response.append(responseLine);
        }
      }
      String responseText = response.toString();
      log.info("Response Code: {}", responseCode);

      if (responseCode >= 400) {
        String extractedMessage = extractSapErrorMessage(responseText);
        result.setSuccess(false);
        result.setErrorMessage(extractedMessage);
        saveSapErrorLog(orderId, requestType, jsonRequest, responseText, extractedMessage);
      } else {
        result.setSuccess(true);
        JsonNode jsonResponse = objectMapper.readTree(responseText);

        String cardCode = null;
        if (jsonResponse.has("CardCode")) {
          cardCode = jsonResponse.get("CardCode").asText();
        }
        result.setCardCode(cardCode);

        if (jsonResponse.has("DocEntry")) {
          String docEntry = jsonResponse.get("DocEntry").asText();
          result.setDocEntry(docEntry);
          saveSapDocEntry(docEntry, orderId, cardCode, requestType, journalTypeCode);
        } else if (jsonResponse.has("JdtNum")) {
          String jdtNum = jsonResponse.get("JdtNum").asText();
          result.setJdtNum(jdtNum);
          saveSapDocEntry(jdtNum, orderId, cardCode, requestType, journalTypeCode);
        }
      }
    } catch (Exception e) {
      log.error("Error while sending JSON request: {}", e.getMessage());
      result.setSuccess(false);
      result.setErrorMessage(e.getMessage());
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
    return result;
  }

  /**
   * Mirrors tracking's {@code Utilities#sendJsonRequest2} — used here only for BusinessPartner
   * (customer) creation. Never throws; on any failure returns a map with both values {@code
   * null}. The original wraps this in an always-already-completed {@code CompletableFuture} —
   * this port returns the map directly, see class Javadoc.
   */
  private Map<String, String> sendJsonRequestForCardCodeAndDocEntry(String requestUrl, Object sapRequest, Long orderId, Integer requestType) {
    HttpURLConnection conn = null;
    String currentCardCode = null;
    String currentDocEntry = null;

    try {
      ObjectMapper objectMapper = new ObjectMapper();
      String jsonRequest = objectMapper.writeValueAsString(sapRequest);
      log.info("JSON Request: {}", jsonRequest);

      URL url = new URL(requestUrl);
      conn = openSapConnection(url);
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setRequestProperty("Key", cinntraKey);
      conn.setRequestProperty("userId", cinntraUserId);

      String sessionId = currentSapSessionId();
      conn.setRequestProperty("Cookie", "B1SESSION=" + sessionId);
      conn.setDoOutput(true);

      try (OutputStream os = conn.getOutputStream()) {
        byte[] input = jsonRequest.getBytes(StandardCharsets.UTF_8);
        os.write(input, 0, input.length);
      } catch (Exception ignored) {
        // matches the original's empty catch here
      }

      int responseCode = conn.getResponseCode();
      StringBuilder response = new StringBuilder();

      try (InputStream inputStream = responseCode < 400 ? conn.getInputStream() : conn.getErrorStream();
          BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
        String responseLine;
        while ((responseLine = br.readLine()) != null) {
          response.append(responseLine);
        }
      } catch (Exception ignored) {
        // matches the original's empty catch here
      }

      String responseText = response.toString();
      log.info("Response Code: {}", responseCode);

      if (responseCode >= 400) {
        String extractedMessage = extractSapErrorMessage(responseText);
        saveSapErrorLog(orderId, requestType, jsonRequest, responseText, extractedMessage);
      } else {
        JsonNode jsonResponse = objectMapper.readTree(responseText);

        if (jsonResponse.has("CardCode")) {
          currentCardCode = jsonResponse.get("CardCode").asText();
        }
        if (jsonResponse.has("DocEntry")) {
          currentDocEntry = jsonResponse.get("DocEntry").asText();
          saveSapDocEntry(currentDocEntry, orderId, currentCardCode, requestType, null);
        }
      }
    } catch (Exception ex) {
      log.error("Error while sending JSON request: {}", ex.getMessage(), ex);
      currentCardCode = null;
      currentDocEntry = null;
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }

    Map<String, String> result = new HashMap<>();
    result.put("cardCode", currentCardCode);
    result.put("docEntry", currentDocEntry);
    return result;
  }

  /**
   * Mirrors tracking's {@code Utilities#sendJsonRequest} (the {@code void}, 7-arg overload) —
   * fire-and-forget: logs to {@link SapErrorLog} on failure, {@link SapDocEntries} on success,
   * but never throws. Used for the IncomingPayments POST.
   */
  private void postFireAndForget(String requestUrl, Object sapRequest, Long orderId, Integer requestType, Integer journalTypeCode) {
    HttpURLConnection conn = null;
    try {
      ObjectMapper objectMapper = new ObjectMapper();
      String jsonRequest = objectMapper.writeValueAsString(sapRequest);
      log.info("JSON Request: {}", jsonRequest);

      URL url = new URL(requestUrl);
      conn = openSapConnection(url);
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setRequestProperty("Key", cinntraKey);
      conn.setRequestProperty("userId", cinntraUserId);

      String sessionId = currentSapSessionId();
      conn.setRequestProperty("Cookie", "B1SESSION=" + sessionId);
      conn.setDoOutput(true);

      try (OutputStream os = conn.getOutputStream()) {
        byte[] input = jsonRequest.getBytes(StandardCharsets.UTF_8);
        os.write(input, 0, input.length);
      }

      int responseCode = conn.getResponseCode();
      StringBuilder response = new StringBuilder();

      try (InputStream inputStream = responseCode < 400 ? conn.getInputStream() : conn.getErrorStream();
          BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
        String responseLine;
        while ((responseLine = br.readLine()) != null) {
          response.append(responseLine);
        }
      }

      String responseText = response.toString();
      log.info("Response Code: {}", responseCode);

      if (responseCode >= 400) {
        String extractedMessage = extractSapErrorMessage(responseText);
        saveSapErrorLog(orderId, requestType, jsonRequest, responseText, extractedMessage);
      } else {
        JsonNode jsonResponse = objectMapper.readTree(responseText);
        String cardCode = jsonResponse.has("CardCode") ? jsonResponse.get("CardCode").asText() : null;

        if (jsonResponse.has("DocEntry")) {
          saveSapDocEntry(jsonResponse.get("DocEntry").asText(), orderId, cardCode, requestType, journalTypeCode);
        } else if (jsonResponse.has("JdtNum")) {
          saveSapDocEntry(jsonResponse.get("JdtNum").asText(), orderId, cardCode, requestType, journalTypeCode);
        }
      }
    } catch (Exception ex) {
      log.error("Error while sending JSON request: {}", ex.getMessage());
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  /**
   * Tracking dereferences the {@code tbl_sap_session} row directly, so a missing row throws before
   * any HTTP call and nothing is sent or logged to {@code tbl_sap_error_log}. Throwing here keeps
   * that: every caller's outer catch handles it without writing an error-log row.
   */
  /**
   * Tracking's {@code HttpURLConnection}s set no timeouts, so a hung SAP socket blocks forever. On
   * the heavy tier that pins one of a handful of slots past the heartbeat timeout — Temporal fails
   * the attempt, but the thread keeps waiting. Bounded here; a timeout surfaces as the same caught
   * exception tracking's code already handles for any other I/O failure.
   */
  private static HttpURLConnection openSapConnection(URL url) throws java.io.IOException {
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setConnectTimeout(SAP_CONNECT_TIMEOUT_MS);
    conn.setReadTimeout(SAP_READ_TIMEOUT_MS);
    return conn;
  }

  private String currentSapSessionId() {
    return sapSessionRepository
        .findById(SAP_SESSION_ID)
        .map(EliteMismatchSyncSapSession::getSessionId)
        .orElseThrow(() -> new IllegalStateException("No SAP session row with id " + SAP_SESSION_ID));
  }

  private void saveSapErrorLog(Long orderId, Integer requestType, String requestJson, String responseJson, String errorMessage) {
    try {
      SapErrorLog errorLog = new SapErrorLog();
      if (orderId != null) {
        errorLog.setOrderId(orderId);
      }
      errorLog.setRequestType(requestType);
      errorLog.setRequestJson(requestJson);
      errorLog.setResponseJson(responseJson);
      errorLog.setErrorMessage(errorMessage);
      errorLog.setCreatedAt(new Timestamp(System.currentTimeMillis()));
      sapErrorLogRepository.save(errorLog);
    } catch (Exception ex) {
      log.error("Failed to persist SapErrorLog for orderId {}: {}", orderId, ex.getMessage(), ex);
    }
  }

  private void saveSapDocEntry(String docEntryValue, Long orderId, String cardCode, Integer requestType, Integer journalTypeCode) {
    SapDocEntries docEntryRecord = new SapDocEntries();
    docEntryRecord.setDocEntry(docEntryValue);
    docEntryRecord.setOrderId(orderId != null ? orderId.intValue() : null);
    docEntryRecord.setSapId(cardCode);
    docEntryRecord.setRequestType(requestType);
    docEntryRecord.setJournalTypeCode(journalTypeCode);
    sapDocEntriesRepository.save(docEntryRecord);
  }

  /**
   * Extracts the SAP Service Layer error message from its JSON error body. Mirrors tracking's
   * {@code Utilities#extractSapErrorMessage} exactly, including both sentinel fallbacks: an
   * unparseable body yields {@code "Unable to parse error message: ..."} and a parseable body with
   * no {@code error.message.value} yields {@code "Unknown SAP error"}. Returning the raw response
   * body instead would risk overflowing {@code tbl_sap_error_log.error_message} and could match
   * the "no matching record" address-retry trigger on unrelated text.
   */
  private String extractSapErrorMessage(String responseText) {
    try {
      JsonNode root = new ObjectMapper().readTree(responseText);
      if (root.has("error")) {
        JsonNode error = root.get("error");
        if (error.has("message") && error.get("message").has("value")) {
          return error.get("message").get("value").asText();
        }
      }
    } catch (Exception ex) {
      return "Unable to parse error message: " + ex.getMessage();
    }
    return "Unknown SAP error";
  }
}
