package com.yesmadam.schedulerservice.jobs.cinntraLoginAndStoreSession.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.yesmadam.schedulerservice.jobs.cinntraLoginAndStoreSession.model.CinntraSapSession;
import com.yesmadam.schedulerservice.jobs.cinntraLoginAndStoreSession.repository.CinntraSapSessionRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Service containing business logic for the Cinntra (SAP B1 Service Layer) login/session-refresh
 * cron. Ports tracking's {@code CintraSession.loginAndStoreSession} 1:1: POST the SAP company
 * credentials to Cinntra's {@code Login} endpoint, extract {@code SessionId} from the response,
 * and upsert it into the single {@code tbl_sap_session} row (id {@code 1}) that every other job in
 * this service reads to authenticate its own Cinntra calls.
 *
 * <p>This is the simplest job in the service so far — one HTTP call, one upsert, no eligibility
 * query, no per-item loop — because that is exactly what the original method does.
 *
 * <p><b>Deliberate deviations from the original, all logging-only (no behavioural/persistence
 * change):</b>
 *
 * <ul>
 *   <li>Structured {@code event=...} log lines replace tracking's literal log text (including the
 *       stray {@code "Sunil Session loginAndStoreSession: "} line, apparently a leftover debug
 *       message from whoever last touched this cron), matching every other job in this service.
 *   <li>A failed login (non-{@code 200} response, missing body, or a thrown exception) is logged
 *       at {@code ERROR} rather than tracking's {@code INFO} — this job silently degrading means
 *       every other Cinntra-calling job starts failing too, so it deserves more visibility than
 *       tracking gave it. The control flow is unchanged: like the original, a failure here is
 *       swallowed and does not throw, so Temporal will not retry a failed login any more than
 *       tracking's own {@code @Scheduled} method would have.
 * </ul>
 *
 * <p>Preserved as-is from the original, not "fixed":
 *
 * <ul>
 *   <li>The success check is strictly {@code response.getStatusCode() == HttpStatus.OK}, not "any
 *       2xx" — a Cinntra response like {@code 201 Created} would fall into the original's "Login
 *       failed" branch even though the call technically succeeded. Reproduced exactly.
 * </ul>
 */
@Slf4j
@Service
public class CinntraLoginAndStoreSessionService {

  private static final Integer SAP_SESSION_ID = 1;
  private static final String LOGIN_PATH = "Login";

  @Value("${com.cinntra.url}")
  private String cinntraBaseUrl;

  @Value("${com.cinntra.sap.companydb}")
  private String sapCompanyDb;

  @Value("${com.cinntra.sap.username}")
  private String sapUsername;

  @Value("${com.cinntra.sap.password}")
  private String sapPassword;

  private final CinntraSapSessionRepository cinntraSapSessionRepository;
  private final RestTemplate restTemplate;

  public CinntraLoginAndStoreSessionService(
      CinntraSapSessionRepository cinntraSapSessionRepository, RestTemplate restTemplate) {
    this.cinntraSapSessionRepository = cinntraSapSessionRepository;
    this.restTemplate = restTemplate;
  }

  /**
   * Logs in to Cinntra and upserts the resulting session id into {@code tbl_sap_session} (id
   * {@code 1}). Never throws — a failure is logged and swallowed, matching the original.
   */
  public void loginAndStoreSession() {
    Map<String, String> payload = new HashMap<>();
    payload.put("CompanyDB", sapCompanyDb);
    payload.put("UserName", sapUsername);
    payload.put("Password", sapPassword);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    HttpEntity<Map<String, String>> request = new HttpEntity<>(payload, headers);

    try {
      String loginUrl = cinntraBaseUrl + LOGIN_PATH;
      ResponseEntity<Map> response = restTemplate.postForEntity(loginUrl, request, Map.class);

      if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
        String sessionId = (String) response.getBody().get("SessionId");
        upsertSession(sessionId);
        log.info("event=cinntra_session_stored session_id_present={}", sessionId != null);
      } else {
        log.error("event=cinntra_login_failed status={}", response.getStatusCode());
      }
    } catch (Exception ex) {
      log.error("event=cinntra_login_exception error={}", ex.getMessage(), ex);
    }
  }

  /**
   * Mirrors tracking's {@code UtilitiesDao.updateSession}: fetch the row with id {@code 1},
   * update it if present, otherwise insert a new row with id {@code 1} explicitly set.
   */
  private void upsertSession(String sessionId) {
    CinntraSapSession session =
        cinntraSapSessionRepository.findById(SAP_SESSION_ID).orElseGet(CinntraSapSession::new);
    if (session.getId() == null) {
      session.setId(SAP_SESSION_ID);
    }
    session.setSessionId(sessionId);
    session.setInsertedAt(LocalDateTime.now());
    cinntraSapSessionRepository.save(session);
  }
}
