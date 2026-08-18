package com.yesmadam.schedulerservice.jobs.passbookmismatch.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.common.mail.MailService;
import com.yesmadam.schedulerservice.jobs.passbookmismatch.model.PassbookMismatchReport;
import com.yesmadam.schedulerservice.jobs.passbookmismatch.model.PassbookMismatchRow;
import com.yesmadam.schedulerservice.jobs.passbookmismatch.projections.PassbookMismatchProjection;
import com.yesmadam.schedulerservice.jobs.passbookmismatch.repository.PassbookMismatchRepository;
import com.yesmadam.schedulerservice.utils.QueryTimer;

import lombok.extern.slf4j.Slf4j;

/**
 * Audits recent {@code tbl_sp_passbook} entries against the two invariants empapi maintains when it
 * writes them, and mails whatever it finds.
 *
 * <ol>
 *   <li><b>Within an entry</b> — {@code closingbalance = openingbalance - amount} for a debit,
 *       {@code openingbalance + amount} for a credit.
 *   <li><b>Between entries</b> — an entry's {@code openingbalance} equals the previous entry's
 *       {@code closingbalance} for the same SP, in {@code id} order.
 * </ol>
 *
 * <p>All of the detection is in the SQL (see {@link PassbookMismatchRepository#findMismatches});
 * this class fixes the window, maps rows, and renders the mail.
 */
@Slf4j
@Service
public class PassbookMismatchServiceImpl implements PassbookMismatchService {

  private final PassbookMismatchRepository mismatchRepository;
  private final MailService mailService;

  @Value("${passbookmismatch.lookback.minutes:30}")
  private int lookbackMinutes;

  @Value("${passbookmismatch.mail.from}")
  private String mailFrom;

  @Value("#{'${passbookmismatch.mail.recipients:}'.split(',')}")
  private List<String> mailRecipients;

  public PassbookMismatchServiceImpl(
      PassbookMismatchRepository mismatchRepository, MailService mailService) {
    this.mismatchRepository = mismatchRepository;
    this.mailService = mailService;
  }

  /**
   * {@inheritDoc}
   *
   * <p>The window ends at the current instant, truncated to whole seconds so the bounds log and
   * compare cleanly against a {@code DATETIME} column with no sub-second part.
   */
  @Override
  @Transactional(readOnly = true)
  public PassbookMismatchReport fetchReport() {
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Timestamp windowEnd = Timestamp.from(now);
    Timestamp windowStart = Timestamp.from(now.minus(lookbackMinutes, ChronoUnit.MINUTES));

    QueryTimer timer = new QueryTimer();
    List<PassbookMismatchProjection> found =
        timer.time(
            "findMismatches", () -> mismatchRepository.findMismatches(windowStart, windowEnd));

    List<PassbookMismatchRow> rows = new ArrayList<>(found.size());
    found.forEach(p -> rows.add(toRow(p)));

    log.info(
        "event=passbook_mismatch_scanned window_start={} window_end={} mismatches={} "
            + "durations_ms={} row_counts={}",
        windowStart,
        windowEnd,
        rows.size(),
        timer.getDurationsMs(),
        timer.getRowCounts());

    return new PassbookMismatchReport(
        windowStart, windowEnd, rows, timer.getDurationsMs(), timer.getRowCounts());
  }

  /**
   * {@inheritDoc}
   *
   * <p>A clean window sends nothing. Mailing "0 mismatches" every 30 minutes — 48 times a day —
   * trains people to filter the alert, and then the one that matters gets filtered too.
   */
  @Override
  public void mailReport(PassbookMismatchReport report) {
    if (report.getRows() == null || report.getRows().isEmpty()) {
      log.info(
          "event=passbook_mismatch_mail_skipped reason=no_mismatches window_start={} window_end={}",
          report.getWindowStart(),
          report.getWindowEnd());
      return;
    }

    String subject =
        String.format(
            "[Passbook Mismatch] %d entr%s flagged between %s and %s",
            report.getRows().size(),
            report.getRows().size() == 1 ? "y" : "ies",
            report.getWindowStart(),
            report.getWindowEnd());

    mailService.sendHtml(mailFrom, recipients(), subject, buildHtml(report));
  }

  /**
   * Recipient list, blanks stripped so a trailing comma in the property cannot produce an empty
   * address.
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

  private PassbookMismatchRow toRow(PassbookMismatchProjection p) {
    return new PassbookMismatchRow(
        p.getPassbookId(),
        p.getSpId(),
        p.getPassbookDate(),
        p.getMismatchReason(),
        p.getOpeningBalance(),
        p.getAmount(),
        p.getClosingBalance(),
        p.getActionType(),
        p.getPrevClosing(),
        p.getExpectedClosing());
  }

  /**
   * Renders the report as a plain HTML table — no template engine, so nothing else has to be wired
   * in.
   */
  private String buildHtml(PassbookMismatchReport report) {
    StringBuilder html = new StringBuilder(1024);
    html.append("<html><body style=\"font-family:Arial,sans-serif;font-size:13px\">")
        .append("<p>SP passbook entries flagged between <b>")
        .append(report.getWindowStart())
        .append("</b> and <b>")
        .append(report.getWindowEnd())
        .append("</b>.</p>");

    html.append("<table border=\"1\" cellpadding=\"6\" cellspacing=\"0\" ")
        .append("style=\"border-collapse:collapse\"><tr style=\"background:#eee\">");
    for (String header :
        Arrays.asList(
            "Passbook ID",
            "SP ID",
            "Entry time",
            "Reason",
            "Opening",
            "Amount",
            "Closing",
            "Type",
            "Expected opening",
            "Expected closing")) {
      html.append("<th align=\"left\">").append(header).append("</th>");
    }
    html.append("</tr>");

    for (PassbookMismatchRow row : report.getRows()) {
      html.append("<tr>")
          .append(cell(row.getPassbookId()))
          .append(cell(row.getSpId()))
          .append(cell(row.getPassbookDate()))
          .append(cell(row.getMismatchReason()))
          .append(cell(row.getOpeningBalance()))
          .append(cell(row.getAmount()))
          .append(cell(row.getClosingBalance()))
          .append(cell(row.getActionType()))
          .append(cell(row.getPrevClosing()))
          .append(cell(row.getExpectedClosing()))
          .append("</tr>");
    }

    html.append("</table>")
        .append("<p style=\"color:#666\">Expected opening = previous entry's closing balance. ")
        .append(
            "Expected closing = opening +/- amount. Blank means the check could not be applied.")
        .append("</p></body></html>");

    return html.toString();
  }

  /**
   * One table cell. NULL renders as an em dash so an empty column is visibly empty, not missing.
   */
  private String cell(Object value) {
    return "<td>" + (value == null ? "&mdash;" : value) + "</td>";
  }
}
