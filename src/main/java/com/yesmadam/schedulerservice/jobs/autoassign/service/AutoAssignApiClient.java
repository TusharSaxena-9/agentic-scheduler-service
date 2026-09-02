package com.yesmadam.schedulerservice.jobs.autoassign.service;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import lombok.extern.slf4j.Slf4j;

/**
 * Outbound HTTP for the auto-assign run — now a single call.
 *
 * <p><b>assign</b> — app-server's {@code assign-sp-to-neworder}, the same endpoint the cron hits
 * today, which does the actual SP selection and assignment.
 *
 * <p>There used to be a second call here, a POST to cron's {@code
 * /cron/trigger/job-distribution/trigger}, which handed rejected orders to the Auto Job
 * Distribution engine inside the cron service. That engine now lives here, so the handoff is an
 * in-process call to {@code AutoJobDistributionService.triggerDistributionBatch} instead — see
 * {@link AutoAssignServiceImpl#dispatchDistributionBatch}. The distribution engine owns its own
 * HTTP client ({@code DistributionAdminClient}) for the one call it makes, to admin.
 *
 * <p><b>Timeouts are set explicitly</b> (5s connect / 10s read) rather than inherited from the
 * shared {@code RestTemplate} bean, which has none. cron got away with an unbounded assign call
 * because it ran on a {@code @Scheduled} thread that could block harmlessly; here the call sits
 * inside a Temporal activity with a 15-minute StartToClose budget shared by up to 100 sequential
 * assigns, so one hung socket could consume the whole chunk's budget and force a retry of orders
 * that had already been assigned. A timed-out assign is reported as a failure for that order alone
 * and retried on the next tick, which is the same outcome cron produced for a rejected assign.
 */
@Slf4j
@Service
public class AutoAssignApiClient {

  @Value("${com.api.yesmadam.base.url}")
  private String appServerBaseUrl;

  @Value("${com.api.yesmadam.assign-sp-to-neworder}")
  private String assignSpToNewOrderPath;

  private final RestTemplate restTemplate;

  public AutoAssignApiClient(RestTemplateBuilder restTemplateBuilder) {
    this.restTemplate =
        restTemplateBuilder
            .setConnectTimeout(Duration.ofSeconds(5))
            .setReadTimeout(Duration.ofSeconds(10))
            .build();
  }

  /**
   * Asks app-server to pick and assign an SP for one order.
   *
   * <p>Never throws: a failed assign for one order must not abort the chunk, exactly as the cron's
   * per-order {@code try/catch} ensures today. This also keeps chunk activities effectively
   * non-throwing <em>after</em> their first side effect, so a Temporal retry can never re-fire
   * assign calls for orders the previous attempt already handled.
   *
   * @param orderId the order to assign
   * @return {@code true} if the API accepted the request
   */
  public boolean triggerAssign(Long orderId) {
    String url = appServerBaseUrl + assignSpToNewOrderPath + "?oid=" + orderId;
    try {
      ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
      boolean ok = response.getStatusCode().is2xxSuccessful();
      log.info(
          "event=auto_assign_called order_id={} status={} body={}",
          orderId,
          response.getStatusCodeValue(),
          response.getBody());
      return ok;
    } catch (RuntimeException e) {
      log.error("event=auto_assign_failed order_id={} url={}", orderId, url, e);
      return false;
    }
  }
}
