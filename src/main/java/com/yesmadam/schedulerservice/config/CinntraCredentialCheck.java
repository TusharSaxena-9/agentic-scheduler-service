package com.yesmadam.schedulerservice.config;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * Reports at startup whether the Cinntra (SAP B1 Service Layer) credentials are actually present.
 *
 * <p>The profile properties default these to empty (e.g. {@code ${CINNTRA_KEY:}}) so the service
 * can be deployed before the values land in Secrets Manager. That is safe on its own — every
 * schedule is registered <b>paused</b>, so nothing reaches SAP until someone triggers a job — but
 * it makes a missing credential silent: without this check the first sign of trouble would be an
 * SAP authentication failure buried in {@code tbl_sap_error_log}, long after deployment.
 *
 * <p>Deliberately a warning rather than a failure: blocking startup would defeat the point of the
 * empty defaults. Twelve job packages authenticate with {@code com.cinntra.key}/{@code
 * com.cinntra.userId}, and {@code cinntraLoginAndStoreSession} additionally needs the three {@code
 * com.cinntra.sap.*} values to refresh the shared session row.
 */
@Slf4j
@Component
public class CinntraCredentialCheck {

  @Value("${com.cinntra.key:}")
  private String cinntraKey;

  @Value("${com.cinntra.userId:}")
  private String cinntraUserId;

  @Value("${com.cinntra.sap.companydb:}")
  private String sapCompanyDb;

  @Value("${com.cinntra.sap.username:}")
  private String sapUsername;

  @Value("${com.cinntra.sap.password:}")
  private String sapPassword;

  @PostConstruct
  void reportCredentialState() {
    List<String> missing = new ArrayList<>();
    addIfBlank(missing, "com.cinntra.key", cinntraKey);
    addIfBlank(missing, "com.cinntra.userId", cinntraUserId);
    addIfBlank(missing, "com.cinntra.sap.companydb", sapCompanyDb);
    addIfBlank(missing, "com.cinntra.sap.username", sapUsername);
    addIfBlank(missing, "com.cinntra.sap.password", sapPassword);

    if (missing.isEmpty()) {
      log.info("event=cinntra_credentials_present");
      return;
    }
    log.warn(
        "event=cinntra_credentials_missing missing_count={} missing={} impact="
            + "\"every SAP call will fail authentication; do not enable any Cinntra schedule "
            + "until these are supplied\"",
        missing.size(),
        missing);
  }

  private static void addIfBlank(List<String> missing, String name, String value) {
    if (!StringUtils.hasText(value)) {
      missing.add(name);
    }
  }
}
