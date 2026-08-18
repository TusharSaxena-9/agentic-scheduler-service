package com.yesmadam.schedulerservice.jobs.passbookmismatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
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
import com.yesmadam.schedulerservice.jobs.passbookmismatch.model.PassbookMismatchReport;
import com.yesmadam.schedulerservice.jobs.passbookmismatch.model.PassbookMismatchRow;
import com.yesmadam.schedulerservice.jobs.passbookmismatch.projections.PassbookMismatchProjection;
import com.yesmadam.schedulerservice.jobs.passbookmismatch.repository.PassbookMismatchRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PassbookMismatchServiceImplTest {

  private static final Timestamp WINDOW_START =
      Timestamp.from(Instant.parse("2026-08-18T11:30:00Z"));
  private static final Timestamp WINDOW_END = Timestamp.from(Instant.parse("2026-08-18T12:00:00Z"));

  @Mock PassbookMismatchRepository mismatchRepository;
  @Mock MailService mailService;
  @InjectMocks PassbookMismatchServiceImpl service;

  private void givenConfig() {
    ReflectionTestUtils.setField(service, "lookbackMinutes", 30);
    ReflectionTestUtils.setField(service, "mailFrom", "no-reply@yesmadam.com");
    ReflectionTestUtils.setField(
        service, "mailRecipients", List.of("amandeep.saini@yesmadam.com", "  ", ""));
  }

  // ================================================================
  // fetchReport
  // ================================================================

  @Test
  void fetchReport_shouldMapProjectionRowsOntoTheReport() {
    when(mismatchRepository.findMismatches(any(), any()))
        .thenReturn(List.of(projection(11L, "OPENING_BALANCE_BREAK")));
    givenConfig();

    PassbookMismatchReport report = service.fetchReport();

    assertThat(report.getRows()).hasSize(1);
    assertThat(report.getRows().get(0).getPassbookId()).isEqualTo(11L);
    assertThat(report.getRows().get(0).getMismatchReason()).isEqualTo("OPENING_BALANCE_BREAK");
    assertThat(report.getWindowStart()).isBefore(report.getWindowEnd());
  }

  @Test
  void fetchReport_shouldReturnEveryMismatchWithNoCap() {
    when(mismatchRepository.findMismatches(any(), any()))
        .thenReturn(
            IntStream.rangeClosed(1, 500)
                .mapToObj(i -> projection((long) i, "MOVEMENT_MISMATCH"))
                .collect(Collectors.toList()));
    givenConfig();

    assertThat(service.fetchReport().getRows()).hasSize(500);
  }

  @Test
  void fetchReport_shouldReturnEmptyRowsForACleanWindow() {
    when(mismatchRepository.findMismatches(any(), any())).thenReturn(Collections.emptyList());
    givenConfig();

    assertThat(service.fetchReport().getRows()).isEmpty();
  }

  @Test
  void fetchReport_shouldCarryQueryTimingAndRowCount() {
    when(mismatchRepository.findMismatches(any(), any()))
        .thenReturn(List.of(projection(11L, "MOVEMENT_MISMATCH")));
    givenConfig();

    PassbookMismatchReport report = service.fetchReport();

    // Timings ride on the report so they land in workflow history and show per run in the Temporal
    // UI.
    assertThat(report.getQueryDurationsMs()).containsKey("findMismatches");
    assertThat(report.getQueryDurationsMs().get("findMismatches")).isNotNegative();
    assertThat(report.getQueryRowCounts()).containsEntry("findMismatches", 1);
  }

  // ================================================================
  // mailReport
  // ================================================================

  @Test
  void mailReport_shouldNotMailACleanWindow() {
    givenConfig();

    service.mailReport(new PassbookMismatchReport(WINDOW_START, WINDOW_END, List.of()));

    verify(mailService, never()).sendHtml(anyString(), anyList(), anyString(), anyString());
  }

  @Test
  void mailReport_shouldMailFindingsWithReasonAndValuesInTheBody() {
    givenConfig();

    service.mailReport(
        new PassbookMismatchReport(
            WINDOW_START, WINDOW_END, List.of(row(11L, "OPENING_BALANCE_BREAK"))));

    ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<List<String>> to = ArgumentCaptor.forClass(List.class);
    verify(mailService).sendHtml(anyString(), to.capture(), subject.capture(), body.capture());

    assertThat(subject.getValue()).contains("1 entry flagged");
    assertThat(body.getValue()).contains("OPENING_BALANCE_BREAK").contains("11");
    // blank entries in the property must not become empty recipients
    assertThat(to.getValue()).containsExactly("amandeep.saini@yesmadam.com");
  }

  @Test
  void mailReport_shouldRenderNullsAsDashesRatherThanTheWordNull() {
    givenConfig();

    PassbookMismatchRow nullFields =
        new PassbookMismatchRow(
            11L, 501, WINDOW_START, "NULL_BALANCE_FIELDS", 100, null, null, "CREDIT", null, null);

    service.mailReport(new PassbookMismatchReport(WINDOW_START, WINDOW_END, List.of(nullFields)));

    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(mailService).sendHtml(anyString(), anyList(), anyString(), body.capture());
    assertThat(body.getValue()).contains("&mdash;").doesNotContain(">null<");
  }

  // ================================================================
  // helpers
  // ================================================================

  private static PassbookMismatchRow row(Long id, String reason) {
    return new PassbookMismatchRow(id, 501, WINDOW_START, reason, 100, 50, 160, "CREDIT", 90, 150);
  }

  private static PassbookMismatchProjection projection(Long id, String reason) {
    return new PassbookMismatchProjection() {
      @Override
      public Long getPassbookId() {
        return id;
      }

      @Override
      public Integer getSpId() {
        return 501;
      }

      @Override
      public Timestamp getPassbookDate() {
        return WINDOW_START;
      }

      @Override
      public String getMismatchReason() {
        return reason;
      }

      @Override
      public Integer getOpeningBalance() {
        return 100;
      }

      @Override
      public Integer getAmount() {
        return 50;
      }

      @Override
      public Integer getClosingBalance() {
        return 160;
      }

      @Override
      public String getActionType() {
        return "CREDIT";
      }

      @Override
      public Integer getPrevClosing() {
        return 90;
      }

      @Override
      public Integer getExpectedClosing() {
        return 150;
      }
    };
  }
}
