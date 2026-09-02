package com.yesmadam.schedulerservice.jobs.autoJobDistribution.service;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionBatchItem;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.JobDistributionConstants;

import lombok.extern.slf4j.Slf4j;

/**
 * Posts distribution instructions to admin's {@code distribute-job-batch} endpoint.
 *
 * <p>Admin answers 202 and then shortlists, notifies and writes asynchronously, so this call is a
 * hand-off, not the work itself. That is why a failure here is recoverable: an advance leaves its
 * round live and a fresh fire leaves its queue row pending, so the next tick retries and nothing is
 * lost or wrongly finalised.
 *
 * <p>Timeouts are set explicitly (5s connect / 10s read, matching cron's {@code
 * sendPostWithCronSecret}) rather than left to {@code RestTemplate}'s unbounded defaults. That
 * matters more here than it did in cron: this runs inside a Temporal activity, and a hung socket
 * would burn the activity's whole StartToClose budget and trigger a retry of the entire pass.
 */
@Slf4j
@Component
public class DistributionAdminClient {

  /** Admin authenticates the call on this header; without it the endpoint rejects the request. */
  private static final String CRON_SECRET_HEADER = "X-Cron-Secret";

  private final RestTemplate restTemplate;

  @Value("${com.api.yesmadam.admin.base.url}")
  private String adminBaseUrl;

  @Value("${com.api.yesmadam.distribute-job-batch}")
  private String distributeJobBatchPath;

  /**
   * Inline default as well as the one in {@code application.properties}. Without it, a missing
   * property makes this bean fail construction and vetoes the whole context — which stops every
   * unrelated job from starting over a secret only this class needs. It has happened twice: once
   * when the property was first added, and again when a merge removed it. The blank-secret check in
   * {@link #post} is what turns the absence into a clear error at the point of use.
   */
  @Value("${cron.secret.key:}")
  private String cronSecretKey;

  public DistributionAdminClient(RestTemplateBuilder restTemplateBuilder) {
    this.restTemplate =
        restTemplateBuilder
            .setConnectTimeout(Duration.ofSeconds(5))
            .setReadTimeout(Duration.ofSeconds(10))
            .build();
  }

  /**
   * Fires one item, unchunked.
   *
   * @return {@code true} only if the call completed without throwing — the caller uses this to
   *     decide whether the claimed queue row may be marked as distributed
   */
  public boolean fireSingle(DistributionBatchItem item) {
    try {
      post(List.of(item));
      return true;
    } catch (RuntimeException ex) {
      log.error(
          "event=distribution_fire_single_failed order_id={} round={} error={}",
          item.getOrderId(),
          item.getRound(),
          ex.getMessage(),
          ex);
      return false;
    }
  }

  /**
   * Fires a batch of advances, chunked. A failed chunk is logged and left for the next tick — the
   * rounds it covers are still live, so they are re-evaluated rather than lost.
   */
  public void fireBatch(List<DistributionBatchItem> items) {
    if (items == null || items.isEmpty()) {
      return;
    }
    int chunkSize = JobDistributionConstants.BATCH_CHUNK_SIZE;
    for (int from = 0; from < items.size(); from += chunkSize) {
      List<DistributionBatchItem> chunk =
          items.subList(from, Math.min(from + chunkSize, items.size()));
      try {
        post(chunk);
      } catch (RuntimeException ex) {
        log.error(
            "event=distribution_fire_batch_chunk_failed size={} error={}",
            chunk.size(),
            ex.getMessage(),
            ex);
      }
    }
  }

  private void post(List<DistributionBatchItem> items) {
    if (cronSecretKey == null || cronSecretKey.trim().isEmpty()) {
      // The secret resolves to empty by default so a local/IDE run can start without it. Admin
      // authenticates on this header, so calling without one would be rejected anyway — failing
      // here
      // names the actual cause instead of surfacing an opaque 401/403 from admin.
      throw new IllegalStateException(
          "cron.secret.key is not configured — set CRON_SECRET_KEY (or the AWS secret) before"
              + " enabling the auto-job-distribution schedule");
    }
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(CRON_SECRET_HEADER, cronSecretKey);
    restTemplate.postForEntity(
        adminBaseUrl + distributeJobBatchPath, new HttpEntity<>(items, headers), String.class);
  }
}
