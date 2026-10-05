package com.yesmadam.schedulerservice.jobs.commissionaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.yesmadam.schedulerservice.common.mail.MailService;
import com.yesmadam.schedulerservice.jobs.commissionaudit.model.CommissionAuditReport;
import com.yesmadam.schedulerservice.jobs.commissionaudit.model.CommissionAuditRow;
import com.yesmadam.schedulerservice.jobs.commissionaudit.projections.CommissionAuditProjection;
import com.yesmadam.schedulerservice.jobs.commissionaudit.repository.CommissionAuditRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommissionAuditServiceImplTest {

  private static final LocalDate BOOKING_DATE = LocalDate.of(2026, 9, 27);

  @Mock CommissionAuditRepository auditRepository;
  @Mock MailService mailService;
  @InjectMocks CommissionAuditServiceImpl service;

  private void givenConfig() {
    ReflectionTestUtils.setField(service, "mailFrom", "no-reply@yesmadam.com");
    ReflectionTestUtils.setField(
        service, "mailRecipients", List.of("amandeep.saini@yesmadam.in", "  ", ""));
  }

  // ================================================================
  // fetchReport
  // ================================================================

  @Test
  void fetchReport_shouldMapRowsAndCountThemByReason() {
    givenConfig();
    when(auditRepository.findFlaggedOrders(Date.valueOf(BOOKING_DATE)))
        .thenReturn(
            List.of(
                projection(11L, 101, "COMMISSION_NOT_DEDUCTED"),
                projection(12L, 102, "COMMISSION_NOT_DEDUCTED"),
                projection(13L, null, "NO_SP_ASSIGNED")));

    CommissionAuditReport report = service.fetchReport(BOOKING_DATE);

    assertThat(report.getBookingDate()).isEqualTo("2026-09-27");
    assertThat(report.getRows())
        .extracting(CommissionAuditRow::getOrderId)
        .containsExactly(11L, 12L, 13L);
    assertThat(report.getRows().get(2).getSpId()).isNull();
    assertThat(report.getCountsByReason())
        .containsEntry("COMMISSION_NOT_DEDUCTED", 2L)
        .containsEntry("NO_SP_ASSIGNED", 1L);
  }

  @Test
  void fetchReport_shouldReturnEveryRowWithNoCap() {
    givenConfig();
    when(auditRepository.findFlaggedOrders(any()))
        .thenReturn(
            IntStream.rangeClosed(1, 5000)
                .mapToObj(i -> projection((long) i, 100 + i, "COMMISSION_NOT_DEDUCTED"))
                .collect(Collectors.toList()));

    CommissionAuditReport report = service.fetchReport(BOOKING_DATE);

    assertThat(report.getRows()).hasSize(5000);
    assertThat(report.getCountsByReason()).containsEntry("COMMISSION_NOT_DEDUCTED", 5000L);
  }

  @Test
  void fetchReport_cleanDay_shouldReturnAnEmptyReport() {
    givenConfig();
    when(auditRepository.findFlaggedOrders(any())).thenReturn(List.of());

    CommissionAuditReport report = service.fetchReport(BOOKING_DATE);

    assertThat(report.getRows()).isEmpty();
    assertThat(report.getCountsByReason()).isEmpty();
  }

  @Test
  void fetchReport_noArg_shouldAuditYesterdayInIst() {
    givenConfig();
    when(auditRepository.findFlaggedOrders(any())).thenReturn(List.of());

    CommissionAuditReport report = service.fetchReport();

    assertThat(report.getBookingDate())
        .isEqualTo(LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1).toString());
  }

  // ================================================================
  // mailReport
  // ================================================================

  @Test
  void mailReport_cleanReport_shouldSendNothing() {
    givenConfig();

    service.mailReport(report(List.of(), Map.of()));

    verify(mailService, never()).sendHtml(anyString(), anyList(), anyString(), anyString());
  }

  @Test
  @SuppressWarnings("unchecked")
  void mailReport_shouldSendToNonBlankRecipientsWithOrderIdsAndSpIds() {
    givenConfig();
    List<CommissionAuditRow> rows =
        List.of(
            new CommissionAuditRow(11L, 101, "COMMISSION_NOT_DEDUCTED"),
            new CommissionAuditRow(13L, null, "NO_SP_ASSIGNED"));

    service.mailReport(
        report(rows, Map.of("COMMISSION_NOT_DEDUCTED", 1L, "NO_SP_ASSIGNED", 1L)));

    ArgumentCaptor<List<String>> to = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(mailService)
        .sendHtml(eq("no-reply@yesmadam.com"), to.capture(), subject.capture(), body.capture());

    assertThat(to.getValue()).containsExactly("amandeep.saini@yesmadam.in");
    assertThat(subject.getValue()).contains("2 orders").contains("2026-09-27");
    assertThat(body.getValue())
        .contains("<td>11</td><td>101</td>")
        // A NULL SP renders as an em dash, not an empty cell.
        .contains("<td>13</td><td>&mdash;</td><td>NO_SP_ASSIGNED</td>");
  }

  @Test
  void mailReport_jobNotCompleted_shouldListTheOrderAndExplainTheReason() {
    givenConfig();
    List<CommissionAuditRow> rows = List.of(new CommissionAuditRow(21L, 201, "JOB_NOT_COMPLETED"));

    service.mailReport(report(rows, Map.of("JOB_NOT_COMPLETED", 1L)));

    ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(mailService).sendHtml(anyString(), anyList(), subject.capture(), body.capture());

    assertThat(subject.getValue()).contains("1 order ");
    assertThat(body.getValue())
        .contains("<td>JOB_NOT_COMPLETED</td><td>1</td>")
        .contains("<td>21</td><td>201</td><td>JOB_NOT_COMPLETED</td>")
        .contains("JOB_NOT_COMPLETED: order is still at start job (status 8)");
  }

  // ================================================================
  // helpers
  // ================================================================

  private static CommissionAuditReport report(
      List<CommissionAuditRow> rows, Map<String, Long> counts) {
    return new CommissionAuditReport(
        BOOKING_DATE.toString(),
        rows,
        new LinkedHashMap<>(counts),
        new LinkedHashMap<>(),
        new LinkedHashMap<>());
  }

  private static CommissionAuditProjection projection(Long orderId, Integer spId, String reason) {
    return new CommissionAuditProjection() {
      @Override
      public Long getOrderId() {
        return orderId;
      }

      @Override
      public Integer getSpId() {
        return spId;
      }

      @Override
      public String getReason() {
        return reason;
      }
    };
  }
}
