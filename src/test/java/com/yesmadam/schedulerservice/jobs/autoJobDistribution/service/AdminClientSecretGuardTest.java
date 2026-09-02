package com.yesmadam.schedulerservice.jobs.autoJobDistribution.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionBatchItem;

/**
 * The cron secret defaults to empty so a local run can start without it. This pins that the client
 * then refuses to call admin, rather than posting unauthenticated and surfacing an opaque 401.
 */
class AdminClientSecretGuardTest {

  private DistributionAdminClient clientWithSecret(String secret) {
    DistributionAdminClient client = new DistributionAdminClient(new RestTemplateBuilder());
    ReflectionTestUtils.setField(client, "adminBaseUrl", "http://admin.invalid/");
    ReflectionTestUtils.setField(
        client, "distributeJobBatchPath", "api/yesmadam/distribute-job-batch");
    ReflectionTestUtils.setField(client, "cronSecretKey", secret);
    return client;
  }

  private DistributionBatchItem item() {
    return new DistributionBatchItem(1L, null, 1, 100);
  }

  @Test
  @DisplayName("fireSingle reports failure rather than calling admin without a secret")
  void fireSingleRefusesWithoutSecret() {
    // false, not an exception: the caller treats it as "not fired", so the queue row stays pending
    // and is retried once the secret is configured.
    assertThat(clientWithSecret("").fireSingle(item())).isFalse();
    assertThat(clientWithSecret(null).fireSingle(item())).isFalse();
    assertThat(clientWithSecret("   ").fireSingle(item())).isFalse();
  }

  @Test
  @DisplayName("fireBatch swallows the missing-secret failure so the pass still completes")
  void fireBatchRefusesWithoutSecret() {
    // Advance rounds stay live on a failed fire, so they are re-evaluated next tick — the pass
    // itself must not blow up.
    clientWithSecret("").fireBatch(List.of(item()));
  }

  @Test
  @DisplayName("an empty batch never touches the network, secret or not")
  void emptyBatchIsANoOp() {
    clientWithSecret("").fireBatch(List.of());
  }
}
