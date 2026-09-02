package com.yesmadam.schedulerservice.jobs.producthublivestock.activity;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.producthublivestock.model.LiveStockAlertReport;
import com.yesmadam.schedulerservice.jobs.producthublivestock.service.ProductHubLiveStockAlertService;

import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;

/**
 * Thin adapter between Temporal and the service layer — all logic lives in {@link
 * ProductHubLiveStockAlertService}.
 *
 * <p>All three activities run on {@code standard}, same as this job's closest analog, {@code
 * PassbookMismatchActivitiesImpl}. Rule 6 in CLAUDE.md suggests routing outbound sends to the
 * {@code notification} queue via a per-method {@code ActivityOptions.setTaskQueue(...)} override in
 * the workflow — but that queue name is only known as a Spring-resolved property placeholder
 * ({@code ${temporal.taskqueue.notification}}), and workflow code cannot use {@code @Value} or read
 * the Spring environment (rule 1). A literal queue name hardcoded in workflow code would duplicate
 * {@code application.properties} and silently drift if the queue were ever renamed there, so this
 * job stays on one queue rather than risk that.
 *
 * <p>WhatsApp template name and recipient numbers are injected here via {@code @Value} rather than
 * in the workflow, since workflow code cannot use Spring injection (CLAUDE.md rule 1).
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.standard}")
public class ProductHubLiveStockAlertActivitiesImpl implements ProductHubLiveStockAlertActivities {

  @Value("${producthublivestock.whatsapp.template.name}")
  private String whatsappTemplate;

  @Value(
      "#{'${producthublivestock.whatsapp.numbers:}'.empty ? null : "
          + "'${producthublivestock.whatsapp.numbers:}'.split(',')}")
  private List<String> whatsappNumbers;

  private final ProductHubLiveStockAlertService productHubLiveStockAlertService;

  public ProductHubLiveStockAlertActivitiesImpl(
      ProductHubLiveStockAlertService productHubLiveStockAlertService) {
    this.productHubLiveStockAlertService = productHubLiveStockAlertService;
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "fetch-live-stock-alert-report")
  @HandleExceptions
  public LiveStockAlertReport fetchLiveStockAlertReport() {
    return productHubLiveStockAlertService.fetchReport();
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "mail-live-stock-alert-report")
  @HandleExceptions
  public void mailLiveStockAlertReport(LiveStockAlertReport report) {
    productHubLiveStockAlertService.mailReport(report);
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "whatsapp-live-stock-alert-report")
  @HandleExceptions
  public void whatsappLiveStockAlertReport(LiveStockAlertReport report) {
    productHubLiveStockAlertService.whatsappReport(report, whatsappTemplate, whatsappNumbers);
  }
}
