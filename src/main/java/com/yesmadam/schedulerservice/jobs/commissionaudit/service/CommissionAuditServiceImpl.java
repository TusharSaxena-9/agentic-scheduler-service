package com.yesmadam.schedulerservice.jobs.commissionaudit.service;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.common.mail.MailService;
import com.yesmadam.schedulerservice.jobs.commissionaudit.model.CommissionAuditReport;
import com.yesmadam.schedulerservice.jobs.commissionaudit.model.CommissionAuditRow;
import com.yesmadam.schedulerservice.jobs.commissionaudit.projections.CommissionAuditProjection;
import com.yesmadam.schedulerservice.jobs.commissionaudit.repository.CommissionAuditRepository;
import com.yesmadam.schedulerservice.utils.QueryTimer;

import lombok.extern.slf4j.Slf4j;

/**
 * Checks that the 03:00 {@code StopJobAndDeductCommission.DeductCommissionIfAny} cron (cron repo,
 * calling empapi's {@code /cron/process-orders}) deducted commission for every order completed
 * yesterday, and mails the orders it missed.
 *
 * <p>All of the detection is in the SQL (see {@link CommissionAuditRepository}). This class fixes
 * the date, maps rows, and renders the mail.
 */
@Slf4j
@Service
public class CommissionAuditServiceImpl implements CommissionAuditService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final CommissionAuditRepository auditRepository;
  private final MailService mailService;

  @Value("${commissionaudit.mail.from}")
  private String mailFrom;

  @Value("#{'${commissionaudit.mail.recipients:}'.split(',')}")
  private List<String> mailRecipients;

  public CommissionAuditServiceImpl(
      CommissionAuditRepository auditRepository, MailService mailService) {
    this.auditRepository = auditRepository;
    this.mailService = mailService;
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(readOnly = true)
  public CommissionAuditReport fetchReport() {
    return fetchReport(LocalDate.now(IST).minusDays(1));
  }

  /** Audits one booking date. Package-private so tests can pin the date. */
  CommissionAuditReport fetchReport(LocalDate bookingDate) {
    Date date = Date.valueOf(bookingDate);

    QueryTimer timer = new QueryTimer();
    List<CommissionAuditProjection> found =
        timer.time("findFlaggedOrders", () -> auditRepository.findFlaggedOrders(date));

    List<CommissionAuditRow> rows = new ArrayList<>(found.size());
    found.forEach(p -> rows.add(toRow(p)));
    Map<String, Long> countsByReason =
        rows.stream()
            .collect(
                Collectors.groupingBy(
                    CommissionAuditRow::getReason, TreeMap::new, Collectors.counting()));

    log.info(
        "event=commission_audit_scanned booking_date={} flagged={} by_reason={} durations_ms={}",
        bookingDate,
        rows.size(),
        countsByReason,
        timer.getDurationsMs());

    return new CommissionAuditReport(
        bookingDate.toString(),
        rows,
        countsByReason,
        timer.getDurationsMs(),
        timer.getRowCounts());
  }

  /**
   * {@inheritDoc}
   *
   * <p>A clean day sends nothing, so the mail only arrives when someone needs to act on it.
   */
  @Override
  public void mailReport(CommissionAuditReport report) {
    if (report.getRows() == null || report.getRows().isEmpty()) {
      log.info(
          "event=commission_audit_mail_skipped reason=no_flagged_orders booking_date={}",
          report.getBookingDate());
      return;
    }

    String subject =
        String.format(
            "[Commission Audit] %d order%s with commission not deducted for %s",
            report.getRows().size(),
            report.getRows().size() == 1 ? "" : "s",
            report.getBookingDate());

    mailService.sendHtml(mailFrom, recipients(), subject, buildHtml(report));
  }

  /**
   * Recipient list with blanks removed, so a trailing comma in the property does not produce an
   * empty address.
   */
  private List<String> recipients() {
    if (mailRecipients == null) {
      return List.of();
    }
    return mailRecipients.stream()
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toList());
  }

  private CommissionAuditRow toRow(CommissionAuditProjection p) {
    return new CommissionAuditRow(p.getOrderId(), p.getSpId(), p.getReason());
  }

  /** Renders the report as a plain HTML summary plus a detail table. */
  private String buildHtml(CommissionAuditReport report) {
    StringBuilder html = new StringBuilder(1024);
    html.append("<html><body style=\"font-family:Arial,sans-serif;font-size:13px\">")
        .append("<p>Orders booked on <b>")
        .append(report.getBookingDate())
        .append("</b> that were missed by the 03:00 commission deduction cron.</p>");

    html.append("<table border=\"1\" cellpadding=\"6\" cellspacing=\"0\" ")
        .append("style=\"border-collapse:collapse;margin-bottom:12px\">")
        .append("<tr style=\"background:#eee\"><th align=\"left\">Reason</th>")
        .append("<th align=\"left\">Orders</th></tr>");
    report
        .getCountsByReason()
        .forEach(
            (reason, count) ->
                html.append("<tr>").append(cell(reason)).append(cell(count)).append("</tr>"));
    html.append("</table>");

    html.append("<table border=\"1\" cellpadding=\"6\" cellspacing=\"0\" ")
        .append("style=\"border-collapse:collapse\"><tr style=\"background:#eee\">");
    for (String header :
        Arrays.asList("Order ID", "SP ID (s_p_assign_by_user)", "Reason")) {
      html.append("<th align=\"left\">").append(header).append("</th>");
    }
    html.append("</tr>");

    for (CommissionAuditRow row : report.getRows()) {
      html.append("<tr>")
          .append(cell(row.getOrderId()))
          .append(cell(row.getSpId()))
          .append(cell(row.getReason()))
          .append("</tr>");
    }

    html.append("</table>")
        .append("<p style=\"color:#666\">")
        .append("NO_SP_ASSIGNED: order completed but s_p_assign_by_user is empty, ")
        .append("so no commission was deducted. ")
        .append("JOB_NOT_COMPLETED: order is still at start job (status 8), the cron could not ")
        .append("complete it, so no commission was deducted. ")
        .append("NO_ORDER_SUPPORT_ROW: no tbl_order_support row. ")
        .append("COMMISSION_NOT_DEDUCTED: payment_status_code is not 3 (commission done). ")
        .append("COMMISSION_PASSBOOK_MISSING: marked deducted, but no commission entry ")
        .append("(reasonCode 11) in tbl_sp_passbook.")
        .append("</p></body></html>");

    return html.toString();
  }

  /** One table cell. NULL renders as an em dash so an empty column is visibly empty. */
  private String cell(Object value) {
    return "<td>" + (value == null ? "&mdash;" : value) + "</td>";
  }
}
