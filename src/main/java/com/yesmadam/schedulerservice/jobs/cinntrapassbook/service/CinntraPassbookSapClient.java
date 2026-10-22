package com.yesmadam.schedulerservice.jobs.cinntrapassbook.service;

import java.io.BufferedReader;
import java.io.IOException;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookBpAddress;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSapDocEntry;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSapErrorLog;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSapResult;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSapSession;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookHubRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookSpAddressRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.repository.CinntraPassbookReadRepository;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.repository.CinntraPassbookSapDocEntryRepository;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.repository.CinntraPassbookSapErrorLogRepository;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.repository.CinntraPassbookSapSessionRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * The SAP Service Layer calls tracking's {@code CinntraPassbook} makes, ported from its {@code
 * Utilities#sendJsonRequestForResult} and the SP-flavoured {@code
 * #sendJsonRequestWithAddressRetry}.
 *
 * <p>Same behaviour, three deliberate differences, each the same one elite-mismatch-sync made:
 *
 * <ul>
 *   <li>Connect and read timeouts. Tracking's connections have none, so a hung SAP socket would pin
 *       a heavy-tier slot until the heartbeat timeout and keep the thread after it.
 *   <li>{@code tbl_sap_error_log.requestJson} gets the JSON body, not tracking's curl command with
 *       the live Key/userId headers in it. Nothing logs those headers either.
 *   <li>The address-retry PATCH takes the SP's hub from the caller, which already loaded it, rather
 *       than re-reading {@code tbl_sp} and {@code tbl_hub} — the same row tracking resolves.
 * </ul>
 *
 * <p>No method here throws: a failure comes back as an unsuccessful {@link
 * CinntraPassbookSapResult}, as tracking's does.
 */
@Slf4j
@Component
public class CinntraPassbookSapClient {

  private static final Integer SAP_SESSION_ID = 1;
  private static final int SAP_CONNECT_TIMEOUT_MS = 10_000;
  private static final int SAP_READ_TIMEOUT_MS = 60_000;
  private static final Duration PATCH_TIMEOUT = Duration.ofMillis(10_000);

  /** tracking's {@code Utilities.STATE_ABBREVIATIONS}; an unknown state maps to {@code ""}. */
  private static final Map<String, String> STATE_ABBREVIATIONS =
      Map.ofEntries(
          Map.entry("andhra pradesh", "AP"),
          Map.entry("arunachal pradesh", "AR"),
          Map.entry("assam", "AS"),
          Map.entry("bihar", "BH"),
          Map.entry("chhattisgarh", "CT"),
          Map.entry("goa", "GA"),
          Map.entry("gujarat", "GJ"),
          Map.entry("haryana", "HR"),
          Map.entry("himachal pradesh", "HP"),
          Map.entry("jharkhand", "JH"),
          Map.entry("karnataka", "KT"),
          Map.entry("madhya pradesh", "MP"),
          Map.entry("maharashtra", "MH"),
          Map.entry("manipur", "MN"),
          Map.entry("meghalaya", "ME"),
          Map.entry("mizoram", "MI"),
          Map.entry("nagaland", "NL"),
          Map.entry("odisha", "OR"),
          Map.entry("punjab", "PB"),
          Map.entry("rajasthan", "RJ"),
          Map.entry("sikkim", "SK"),
          Map.entry("tamil nadu", "TN"),
          Map.entry("telangana", "TL"),
          Map.entry("tripura", "TR"),
          Map.entry("uttar pradesh", "UP"),
          Map.entry("uttarakhand", "UK"),
          Map.entry("west bengal", "WB"),
          Map.entry("delhi", "DL"),
          Map.entry("dadra and nagar haveli", "DN"),
          Map.entry("daman and diu", "DD"),
          Map.entry("chandigarh", "CH"),
          Map.entry("andaman and nicobar islands", "AN"),
          Map.entry("jammu and kashmir", "JK"),
          Map.entry("ladakh", "LA"),
          Map.entry("lakshadweep islands", "LD"),
          Map.entry("pondicherry", "PY"));

  @Value("${com.cinntra.url}")
  private String cinntraBaseUrl;

  @Value("${com.cinntra.key}")
  private String cinntraKey;

  @Value("${com.cinntra.userId}")
  private String cinntraUserId;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private final CinntraPassbookSapSessionRepository sapSessionRepository;
  private final CinntraPassbookSapDocEntryRepository sapDocEntryRepository;
  private final CinntraPassbookSapErrorLogRepository sapErrorLogRepository;
  private final CinntraPassbookReadRepository readRepository;

  /** Wires the session, doc-entry and error-log repositories, and the reader for addresses. */
  public CinntraPassbookSapClient(
      CinntraPassbookSapSessionRepository sapSessionRepository,
      CinntraPassbookSapDocEntryRepository sapDocEntryRepository,
      CinntraPassbookSapErrorLogRepository sapErrorLogRepository,
      CinntraPassbookReadRepository readRepository) {
    this.sapSessionRepository = sapSessionRepository;
    this.sapDocEntryRepository = sapDocEntryRepository;
    this.sapErrorLogRepository = sapErrorLogRepository;
    this.readRepository = readRepository;
  }

  /**
   * tracking's {@code sendJsonRequestForResult}: POST {@code body} to {@code <base>/<path>}, record
   * a {@code tbl_sap_doc_entries} row on success or a {@code tbl_sap_error_log} row on an HTTP
   * error.
   */
  public CinntraPassbookSapResult post(
      String path, Object body, Long orderId, Integer requestType, Integer journalTypeCode) {
    CinntraPassbookSapResult result = new CinntraPassbookSapResult();
    HttpURLConnection conn = null;
    try {
      String jsonRequest = objectMapper.writeValueAsString(body);
      log.info("JSON Request: {}", jsonRequest);

      String sessionId = currentSapSessionId();
      conn = (HttpURLConnection) new URL(cinntraBaseUrl + path).openConnection();
      conn.setConnectTimeout(SAP_CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(SAP_READ_TIMEOUT_MS);
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setRequestProperty("Key", cinntraKey);
      conn.setRequestProperty("userId", cinntraUserId);
      conn.setRequestProperty("Cookie", "B1SESSION=" + sessionId);
      conn.setDoOutput(true);

      try (OutputStream os = conn.getOutputStream()) {
        os.write(jsonRequest.getBytes(StandardCharsets.UTF_8));
      }

      int responseCode = conn.getResponseCode();
      result.setResponseCode(responseCode);
      String responseText = readBody(conn, responseCode);
      log.info("Response Code: {}", responseCode);

      if (responseCode >= 400) {
        String extractedMessage = extractSapErrorMessage(responseText);
        result.setSuccess(false);
        result.setErrorMessage(extractedMessage);
        saveSapErrorLog(orderId, requestType, jsonRequest, responseText, extractedMessage);
      } else {
        result.setSuccess(true);
        JsonNode jsonResponse = objectMapper.readTree(responseText);
        String cardCode =
            jsonResponse.has("CardCode") ? jsonResponse.get("CardCode").asText() : null;
        result.setCardCode(cardCode);
        if (jsonResponse.has("DocEntry")) {
          result.setDocEntry(jsonResponse.get("DocEntry").asText());
          saveSapDocEntry(result.getDocEntry(), orderId, cardCode, requestType, journalTypeCode);
        } else if (jsonResponse.has("JdtNum")) {
          result.setJdtNum(jsonResponse.get("JdtNum").asText());
          saveSapDocEntry(result.getJdtNum(), orderId, cardCode, requestType, journalTypeCode);
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
   * tracking's SP-flavoured {@code sendJsonRequestWithAddressRetry}: {@link #post}, and when SAP
   * answers "no matching record" for the SP's BusinessPartner, PATCH its addresses from {@code
   * tbl_sp_address} and retry exactly once.
   */
  public CinntraPassbookSapResult postWithAddressRetry(
      String path,
      Object body,
      Long orderId,
      Integer requestType,
      Integer journalTypeCode,
      Integer spId,
      CinntraPassbookHubRow spHub,
      String sapCardCode) {
    CinntraPassbookSapResult result = post(path, body, orderId, requestType, journalTypeCode);
    if (!isNoMatchingRecordError(result)
        || spId == null
        || sapCardCode == null
        || sapCardCode.isEmpty()) {
      return result;
    }

    log.info(
        "[SAP-ADDR-RETRY] '{}' for spId={} cardCode={} — attempting address PATCH + single retry",
        result.getErrorMessage(),
        spId,
        sapCardCode);
    try {
      List<CinntraPassbookSpAddressRow> addresses = readRepository.findSpAddresses(spId);
      CinntraPassbookSpAddressRow address = addresses.isEmpty() ? null : addresses.get(0);
      Map<String, Object> patchPayload = new HashMap<>();
      patchPayload.put(
          "BPAddresses",
          List.of(bpAddress(address, spHub, "bo_BillTo"), bpAddress(address, spHub, "bo_ShipTo")));
      boolean patchOk =
          patch(cinntraBaseUrl + "BusinessPartners('" + sapCardCode + "')", patchPayload);
      log.info("[SAP-ADDR-RETRY] Address PATCH for cardCode={} success={}", sapCardCode, patchOk);

      CinntraPassbookSapResult retryResult =
          post(path, body, orderId, requestType, journalTypeCode);
      if (retryResult.isSuccess()) {
        log.info("[SAP-ADDR-RETRY] Retry succeeded for spId={} cardCode={}", spId, sapCardCode);
      } else {
        log.warn(
            "[SAP-ADDR-RETRY] Retry also failed for spId={}"
                + " cardCode={} — giving up (no further retries): {}",
            spId,
            sapCardCode,
            retryResult.getErrorMessage());
      }
      return retryResult;
    } catch (Exception e) {
      log.error(
          "[SAP-ADDR-RETRY] Unexpected error during address PATCH/retry for spId={} cardCode={}",
          spId,
          sapCardCode,
          e);
      return result;
    }
  }

  private static boolean isNoMatchingRecordError(CinntraPassbookSapResult result) {
    // SAP's text is "No matching records found (ODBC -2028)"; tracking matches the stem.
    return !result.isSuccess()
        && result.getErrorMessage() != null
        && result.getErrorMessage().toLowerCase().contains("no matching record");
  }

  private static CinntraPassbookBpAddress bpAddress(
      CinntraPassbookSpAddressRow address, CinntraPassbookHubRow hub, String addressType) {
    CinntraPassbookBpAddress bp = new CinntraPassbookBpAddress();
    bp.setAddressName(hub != null && hub.getHub() != null ? hub.getHub() : "");
    if (address != null) {
      String street = truncateTo100Chars(address.getAddress1());
      bp.setStreet(street);
      bp.setBlock(street);
      bp.setZipCode(address.getPincode() != null ? address.getPincode() : 0);
      bp.setCity(address.getCity() != null ? address.getCity() : "");
    }
    bp.setCounty("INDIA");
    String state = hub != null && hub.getState() != null ? hub.getState() : "";
    bp.setState(STATE_ABBREVIATIONS.getOrDefault(state.toLowerCase(), ""));
    bp.setAddressType(addressType);
    bp.setGstin("");
    bp.setGstType("");
    return bp;
  }

  private static String truncateTo100Chars(String value) {
    if (value == null) {
      return "";
    }
    return value.length() > 100 ? value.substring(0, 100) : value;
  }

  /** tracking's {@code sendPatchJsonRequest}. */
  private boolean patch(String url, Object payload) {
    try {
      String jsonRequest = objectMapper.writeValueAsString(payload);
      log.info("[SAP-ADDR-RETRY] PATCH JSON Request: {}", jsonRequest);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(PATCH_TIMEOUT)
              .header("Content-Type", "application/json")
              .header("Key", cinntraKey)
              .header("userId", cinntraUserId)
              .header("Cookie", "B1SESSION=" + currentSapSessionId())
              .method(
                  "PATCH", HttpRequest.BodyPublishers.ofString(jsonRequest, StandardCharsets.UTF_8))
              .build();
      HttpResponse<String> response =
          HttpClient.newBuilder()
              .connectTimeout(PATCH_TIMEOUT)
              .build()
              .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      log.info("[SAP-ADDR-RETRY] PATCH Response Code: {}", response.statusCode());
      if (response.statusCode() >= 400) {
        log.error(
            "[SAP-ADDR-RETRY] PATCH failed ({}): {}",
            response.statusCode(),
            extractSapErrorMessage(response.body()));
        return false;
      }
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.error("[SAP-ADDR-RETRY] PATCH interrupted", e);
      return false;
    } catch (Exception e) {
      log.error("[SAP-ADDR-RETRY] Error while sending PATCH request: {}", e.getMessage(), e);
      return false;
    }
  }

  private static String readBody(HttpURLConnection conn, int responseCode) throws IOException {
    StringBuilder response = new StringBuilder();
    try (InputStream in = responseCode < 400 ? conn.getInputStream() : conn.getErrorStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) {
        response.append(line);
      }
    }
    return response.toString();
  }

  /**
   * Tracking dereferences the {@code tbl_sap_session} row directly, so a missing row fails the post
   * before any HTTP call and writes no error-log row. Throwing here, inside {@link #post}'s catch,
   * keeps exactly that.
   */
  private String currentSapSessionId() {
    return sapSessionRepository
        .findById(SAP_SESSION_ID)
        .map(CinntraPassbookSapSession::getSessionId)
        .orElseThrow(
            () -> new IllegalStateException("No SAP session row with id " + SAP_SESSION_ID));
  }

  private void saveSapErrorLog(
      Long orderId,
      Integer requestType,
      String requestJson,
      String responseJson,
      String errorMessage) {
    try {
      CinntraPassbookSapErrorLog errorLog = new CinntraPassbookSapErrorLog();
      errorLog.setOrderId(orderId);
      errorLog.setRequestType(requestType);
      errorLog.setRequestJson(requestJson);
      errorLog.setResponseJson(responseJson);
      errorLog.setErrorMessage(errorMessage);
      errorLog.setCreatedAt(new Timestamp(System.currentTimeMillis()));
      sapErrorLogRepository.save(errorLog);
    } catch (Exception e) {
      log.error("Failed to persist SapErrorLog for orderId {}: {}", orderId, e.getMessage(), e);
    }
  }

  private void saveSapDocEntry(
      String docEntryValue,
      Long orderId,
      String cardCode,
      Integer requestType,
      Integer journalTypeCode) {
    CinntraPassbookSapDocEntry entry = new CinntraPassbookSapDocEntry();
    entry.setDocEntry(docEntryValue);
    entry.setOrderId(orderId != null ? orderId.intValue() : null);
    entry.setSapId(cardCode);
    entry.setRequestType(requestType);
    entry.setJournalTypeCode(journalTypeCode);
    sapDocEntryRepository.save(entry);
  }

  /** tracking's {@code extractSapErrorMessage}, including both sentinel fallbacks. */
  private String extractSapErrorMessage(String responseText) {
    try {
      JsonNode root = objectMapper.readTree(responseText);
      if (root.has("error")) {
        JsonNode error = root.get("error");
        if (error.has("message") && error.get("message").has("value")) {
          return error.get("message").get("value").asText();
        }
      }
    } catch (Exception e) {
      return "Unable to parse error message: " + e.getMessage();
    }
    return "Unknown SAP error";
  }
}
