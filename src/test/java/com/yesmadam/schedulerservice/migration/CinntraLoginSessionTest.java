package com.yesmadam.schedulerservice.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.yesmadam.schedulerservice.jobs.cinntraLoginAndStoreSession.model.CinntraSapSession;
import com.yesmadam.schedulerservice.jobs.cinntraLoginAndStoreSession.repository.CinntraSapSessionRepository;
import com.yesmadam.schedulerservice.jobs.cinntraLoginAndStoreSession.service.CinntraLoginAndStoreSessionService;

/**
 * Behaviour of the SAP session refresh (job 9) — the one every other Cinntra job, in this service
 * and in tracking, authenticates with.
 *
 * <p>The safety property that matters: a failed login must leave {@code tbl_sap_session} untouched
 * rather than overwriting a working session with a broken one.
 */
class CinntraLoginSessionTest {

  private CinntraLoginAndStoreSessionService service;
  private Object[] args;

  @BeforeEach
  void setUp() throws Exception {
    Constructor<?> ctor = CinntraLoginAndStoreSessionService.class.getDeclaredConstructors()[0];
    ctor.setAccessible(true);
    args = Arrays.stream(ctor.getParameterTypes()).map(Mockito::mock).toArray();
    service = (CinntraLoginAndStoreSessionService) ctor.newInstance(args);

    ReflectionTestUtils.setField(service, "cinntraBaseUrl", "http://sap.invalid/b1s/v1/");
    ReflectionTestUtils.setField(service, "sapCompanyDb", "COMPANY_DB");
    ReflectionTestUtils.setField(service, "sapUsername", "sap-user");
    ReflectionTestUtils.setField(service, "sapPassword", "sap-pass");
  }

  @SuppressWarnings("unchecked")
  private <R> R collaborator(Class<R> type) {
    return (R) Arrays.stream(args).filter(type::isInstance).findFirst().orElseThrow();
  }

  @SuppressWarnings("unchecked")
  private void givenLoginResponds(ResponseEntity<Map> response) {
    when(collaborator(RestTemplate.class)
            .postForEntity(any(String.class), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(response);
  }

  @Test
  void storesTheSessionIdOnASuccessfulLogin() {
    CinntraSapSessionRepository sessions = collaborator(CinntraSapSessionRepository.class);
    when(sessions.findById(1)).thenReturn(Optional.empty());
    givenLoginResponds(ResponseEntity.ok(Map.of("SessionId", "SESSION-ABC")));

    service.loginAndStoreSession();

    ArgumentCaptor<CinntraSapSession> saved = ArgumentCaptor.forClass(CinntraSapSession.class);
    verify(sessions).save(saved.capture());
    assertThat(saved.getValue().getId()).as("the single session row is always id 1").isEqualTo(1);
    assertThat(saved.getValue().getSessionId()).isEqualTo("SESSION-ABC");
    assertThat(saved.getValue().getInsertedAt()).isNotNull();
  }

  @Test
  void updatesTheExistingRowRatherThanInsertingASecond() {
    CinntraSapSessionRepository sessions = collaborator(CinntraSapSessionRepository.class);
    CinntraSapSession existing = new CinntraSapSession();
    existing.setId(1);
    existing.setSessionId("OLD-SESSION");
    when(sessions.findById(1)).thenReturn(Optional.of(existing));
    givenLoginResponds(ResponseEntity.ok(Map.of("SessionId", "NEW-SESSION")));

    service.loginAndStoreSession();

    ArgumentCaptor<CinntraSapSession> saved = ArgumentCaptor.forClass(CinntraSapSession.class);
    verify(sessions).save(saved.capture());
    assertThat(saved.getValue()).isSameAs(existing);
    assertThat(saved.getValue().getSessionId()).isEqualTo("NEW-SESSION");
  }

  @Test
  void leavesTheSessionUntouchedOnANonOkResponse() {
    CinntraSapSessionRepository sessions = collaborator(CinntraSapSessionRepository.class);
    givenLoginResponds(new ResponseEntity<>(Map.of("SessionId", "IGNORED"), HttpStatus.ACCEPTED));

    service.loginAndStoreSession();

    verify(sessions, never()).save(any());
  }

  @Test
  void leavesTheSessionUntouchedWhenTheCallThrows() {
    CinntraSapSessionRepository sessions = collaborator(CinntraSapSessionRepository.class);
    when(collaborator(RestTemplate.class)
            .postForEntity(any(String.class), any(HttpEntity.class), eq(Map.class)))
        .thenThrow(new RestClientException("connection refused"));

    assertThatCode(() -> service.loginAndStoreSession())
        .as("a failed refresh is swallowed, matching tracking")
        .doesNotThrowAnyException();
    verify(sessions, never()).save(any());
  }

  @Test
  void sendsTheConfiguredCompanyDbAndCredentials() {
    CinntraSapSessionRepository sessions = collaborator(CinntraSapSessionRepository.class);
    when(sessions.findById(1)).thenReturn(Optional.empty());
    givenLoginResponds(ResponseEntity.ok(Map.of("SessionId", "S")));

    service.loginAndStoreSession();

    ArgumentCaptor<HttpEntity> sent = ArgumentCaptor.forClass(HttpEntity.class);
    verify(collaborator(RestTemplate.class))
        .postForEntity(eq("http://sap.invalid/b1s/v1/Login"), sent.capture(), eq(Map.class));
    Map<String, String> body = (Map<String, String>) sent.getValue().getBody();
    assertThat(body)
        .containsEntry("CompanyDB", "COMPANY_DB")
        .containsEntry("UserName", "sap-user")
        .containsEntry("Password", "sap-pass");
  }
}
