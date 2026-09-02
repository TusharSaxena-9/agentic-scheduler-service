package com.yesmadam.schedulerservice.jobs.producthublivestock.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.persistence.SettingsForEnalbeDisable;
import com.yesmadam.schedulerservice.common.mail.MailService;
import com.yesmadam.schedulerservice.common.whatsapp.service.WhatsappAlertService;
import com.yesmadam.schedulerservice.jobs.producthublivestock.enums.ProductHubLiveStockAlertConfig;
import com.yesmadam.schedulerservice.jobs.producthublivestock.model.LiveStockAlertReport;
import com.yesmadam.schedulerservice.jobs.producthublivestock.model.LiveStockAlertRow;
import com.yesmadam.schedulerservice.jobs.producthublivestock.projections.LiveStockAlertProjection;
import com.yesmadam.schedulerservice.jobs.producthublivestock.repository.LiveStockAlertRepository;
import com.yesmadam.schedulerservice.service.interfaces.SettingService;
import com.yesmadam.schedulerservice.utils.QueryTimer;
import com.yesmadam.schedulerservice.utils.SettingConfigUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * Checks {@code tbl_product_inventory_live.live_stock_new} ("Uni Live Stock" on the admin
 * product-hub-inventory page) for every configured hub &times; product combination, and alerts by
 * mail and WhatsApp when it is at or below zero.
 *
 * <p>Unlike {@code PassbookMismatchServiceImpl} this is not a window audit — there is no trailing
 * period to bound, just a live snapshot of the current row values, so {@code detectedAt} is simply
 * "now" (Asia/Kolkata), computed here rather than in workflow code per CLAUDE.md rule 1.
 */
@Slf4j
@Service
public class ProductHubLiveStockAlertServiceImpl implements ProductHubLiveStockAlertService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final DateTimeFormatter DETECTED_AT_FORMAT =
      DateTimeFormatter.ofPattern("MMMM, yyyy HH:mm:ss");
  private static final String SOURCE = "Uniware";

  private final LiveStockAlertRepository liveStockAlertRepository;
  private final SettingService settingService;
  private final MailService mailService;
  private final WhatsappAlertService whatsappAlertService;

  @Value("${producthublivestock.mail.from}")
  private String mailFrom;

  @Value("#{'${producthublivestock.mail.recipients:}'.split(',')}")
  private List<String> mailRecipients;

  public ProductHubLiveStockAlertServiceImpl(
      LiveStockAlertRepository liveStockAlertRepository,
      SettingService settingService,
      MailService mailService,
      WhatsappAlertService whatsappAlertService) {
    this.liveStockAlertRepository = liveStockAlertRepository;
    this.settingService = settingService;
    this.mailService = mailService;
    this.whatsappAlertService = whatsappAlertService;
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(readOnly = true)
  public LiveStockAlertReport fetchReport() {
    String detectedAt = formatDetectedAt(LocalDateTime.now(IST));

    Map<String, SettingsForEnalbeDisable> cfg =
        settingService.findSettingWithKeys(
            List.of(
                ProductHubLiveStockAlertConfig.HUB_IDS.key(),
                ProductHubLiveStockAlertConfig.PRODUCT_IDS.key()));
    List<Integer> hubIds =
        SettingConfigUtil.intListConfig(cfg, ProductHubLiveStockAlertConfig.HUB_IDS.key());
    List<Integer> productIds =
        SettingConfigUtil.intListConfig(cfg, ProductHubLiveStockAlertConfig.PRODUCT_IDS.key());

    if (hubIds.isEmpty() || productIds.isEmpty()) {
      log.info(
          "event=live_stock_check_skipped reason=no_hub_or_product_ids hub_ids={} product_ids={}",
          hubIds.size(),
          productIds.size());
      return new LiveStockAlertReport(detectedAt, List.of());
    }

    QueryTimer timer = new QueryTimer();
    List<LiveStockAlertProjection> found =
        timer.time("findOutOfStock", () -> liveStockAlertRepository.findOutOfStock(hubIds, productIds));

    List<LiveStockAlertRow> rows = new ArrayList<>(found.size());
    found.forEach(p -> rows.add(toRow(p)));

    log.info(
        "event=live_stock_checked hub_ids={} product_ids={} flagged={} durations_ms={} "
            + "row_counts={}",
        hubIds.size(),
        productIds.size(),
        rows.size(),
        timer.getDurationsMs(),
        timer.getRowCounts());

    return new LiveStockAlertReport(
        detectedAt, rows, timer.getDurationsMs(), timer.getRowCounts());
  }

  /**
   * {@inheritDoc}
   *
   * <p>A clean check sends nothing — mailing "0 flagged" three times a day trains people to filter
   * the alert.
   */
  @Override
  public void mailReport(LiveStockAlertReport report) {
    if (report.getRows() == null || report.getRows().isEmpty()) {
      log.info("event=mail_skipped reason=no_out_of_stock_rows");
      return;
    }

    long hubCount = report.getRows().stream().map(LiveStockAlertRow::getHubId).distinct().count();
    String subject =
        String.format(
            "[Live Stock Alert] %d product(s) out of stock across %d hub(s)",
            report.getRows().size(), hubCount);

    mailService.sendHtml(mailFrom, recipients(), subject, buildHtml(report));
  }

  /**
   * {@inheritDoc}
   *
   * <p>One WhatsApp send per hub per configured number — a separate message for each hub, rather
   * than one message listing every hub, so a recipient scanning WhatsApp sees one message per
   * affected hub instead of having to read a multi-hub block.
   */
  @Override
  public void whatsappReport(
      LiveStockAlertReport report, String templateName, List<String> whatsappNumbers) {
    if (report.getRows() == null || report.getRows().isEmpty()) {
      log.info("event=whatsapp_skipped reason=no_out_of_stock_rows");
      return;
    }
    List<String> numbers = blankFiltered(whatsappNumbers);
    if (numbers.isEmpty()) {
      log.info("event=whatsapp_skipped reason=no_recipients");
      return;
    }

    for (Map.Entry<String, List<String>> hub : groupRowsByHub(report).entrySet()) {
      String skuBlock = hub.getKey() + " Product Hub: " + String.join(", ", hub.getValue());
      for (String number : numbers) {
        whatsappAlertService.sendWhatsappSms(
            number, templateName, List.of(skuBlock, SOURCE, report.getDetectedAt()));
      }
    }
  }

  /**
   * Recipient list, blanks stripped so a trailing comma in the property cannot produce an empty
   * address.
   */
  private List<String> recipients() {
    return blankFiltered(mailRecipients);
  }

  private static List<String> blankFiltered(List<String> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream()
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toList());
  }

  private LiveStockAlertRow toRow(LiveStockAlertProjection p) {
    return new LiveStockAlertRow(
        p.getHubId(), p.getHubName(), p.getProductId(), p.getProductName(), p.getLiveStock());
  }

  /**
   * Renders the report as a plain HTML table with one row per hub — every flagged product at that
   * hub comma-separated in a single "Product Name" cell — rather than one row per hub/product pair.
   */
  private String buildHtml(LiveStockAlertReport report) {
    StringBuilder html = new StringBuilder(1024);
    html.append("<html><body style=\"font-family:Arial,sans-serif;font-size:13px\">")
        .append("<p>Dear Team,</p>")
        .append("<p>Please find below the out-of-stock summary for active Product Hubs.</p>")
        .append("<p>Detected at <b>")
        .append(report.getDetectedAt())
        .append("</b> (source: ")
        .append(SOURCE)
        .append(").</p>");

    html.append("<table border=\"1\" cellpadding=\"6\" cellspacing=\"0\" ")
        .append("style=\"border-collapse:collapse\"><tr style=\"background:#eee\">")
        .append("<th align=\"left\">Product Hub</th>")
        .append("<th align=\"left\">Product Name</th>")
        .append("</tr>");

    for (Map.Entry<String, List<String>> hub : groupRowsByHub(report).entrySet()) {
      html.append("<tr><td>")
          .append(hub.getKey())
          .append("</td><td>")
          .append(String.join(", ", hub.getValue()))
          .append("</td></tr>");
    }

    html.append("</table></body></html>");
    return html.toString();
  }

  /** Flagged product names grouped by hub, in the order the query returned them. */
  private Map<String, List<String>> groupRowsByHub(LiveStockAlertReport report) {
    Map<String, List<String>> productsByHub = new LinkedHashMap<>();
    for (LiveStockAlertRow row : report.getRows()) {
      productsByHub.computeIfAbsent(row.getHubName(), k -> new ArrayList<>()).add(row.getProductName());
    }
    return productsByHub;
  }

  /** e.g. {@code "16th June, 2026 00:00:00"}. */
  private static String formatDetectedAt(LocalDateTime dt) {
    return ordinal(dt.getDayOfMonth()) + " " + dt.format(DETECTED_AT_FORMAT);
  }

  private static String ordinal(int day) {
    if (day >= 11 && day <= 13) {
      return day + "th";
    }
    switch (day % 10) {
      case 1:
        return day + "st";
      case 2:
        return day + "nd";
      case 3:
        return day + "rd";
      default:
        return day + "th";
    }
  }
}
