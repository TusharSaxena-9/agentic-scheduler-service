package com.yesmadam.schedulerservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;


/**
 * The empty-default strategy only works if a blank credential is loud at startup rather than
 * silent — otherwise the first symptom is an SAP auth failure inside a job, days later.
 */
class CinntraCredentialCheckTest {

  private static CinntraCredentialCheck withValues(Map<String, String> values) {
    CinntraCredentialCheck check = new CinntraCredentialCheck();
    values.forEach((field, value) -> ReflectionTestUtils.setField(check, field, value));
    return check;
  }

  private static final Map<String, String> ALL_PRESENT =
      Map.of(
          "cinntraKey", "K",
          "cinntraUserId", "U",
          "sapCompanyDb", "DB",
          "sapUsername", "user",
          "sapPassword", "pass");

  @Test
  void neverBlocksStartupWhenEverythingIsMissing() {
    CinntraCredentialCheck check =
        withValues(
            Map.of(
                "cinntraKey", "",
                "cinntraUserId", "",
                "sapCompanyDb", "",
                "sapUsername", "",
                "sapPassword", ""));

    assertThatCode(check::reportCredentialState)
        .as("blocking would defeat the purpose of the empty defaults")
        .doesNotThrowAnyException();
  }

  @Test
  void treatsWhitespaceOnlyValuesAsMissing() {
    CinntraCredentialCheck check =
        withValues(
            Map.of(
                "cinntraKey", "   ",
                "cinntraUserId", "U",
                "sapCompanyDb", "DB",
                "sapUsername", "user",
                "sapPassword", "pass"));

    assertThatCode(check::reportCredentialState).doesNotThrowAnyException();
  }

  @Test
  void isSatisfiedWhenAllFiveArePresent() {
    assertThat(ALL_PRESENT).hasSize(5);
    assertThatCode(withValues(ALL_PRESENT)::reportCredentialState).doesNotThrowAnyException();
  }
}
