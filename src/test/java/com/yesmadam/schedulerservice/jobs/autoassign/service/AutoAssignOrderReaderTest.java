package com.yesmadam.schedulerservice.jobs.autoassign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignScope;
import com.yesmadam.schedulerservice.jobs.autoassign.projections.UnassignedOrderProjection;
import com.yesmadam.schedulerservice.jobs.autoassign.repository.AutoAssignOrderRepository;

/**
 * Pins the numeric narrowing on the native reads — the failure mode that a green build and a mocked
 * service test both miss entirely.
 *
 * <p>These queries are native, so the JDBC driver decides the Java types, not the method signature.
 * MySQL returns {@code BigInteger} for a {@code BIGINT UNSIGNED} column and {@code BigDecimal} for
 * a {@code SUM()}, and neither is a {@code Long} or an {@code Integer}. The production code narrows
 * through {@link Number} for exactly this reason; these tests feed the reader the types MySQL
 * actually produces and assert it does not throw.
 *
 * <p><b>What this does not prove.</b> The repository is mocked, so this pins the conversion but not
 * the alias-to-getter binding of the projection interfaces, nor that the SQL itself is valid — both
 * are resolved by Hibernate at first execution against a real database. Closing that needs a run
 * against MySQL; see the migration notes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutoAssignOrderReaderTest {

  private static final long ORDER_ID = 5154874L;

  @Mock AutoAssignOrderRepository repository;

  @InjectMocks AutoAssignOrderReader reader;

  @Test
  @DisplayName("reassign counts survive MySQL's BigInteger id and BigDecimal SUM")
  void reassignCountsNarrowFromBigNumberTypes() {
    when(repository.findReassignCounts(anyList()))
        .thenReturn(
            List.<Object[]>of(
                new Object[] {BigInteger.valueOf(ORDER_ID), BigDecimal.valueOf(3)},
                new Object[] {BigInteger.valueOf(ORDER_ID + 1), BigInteger.valueOf(0)}));

    Map<Long, Integer> counts = reader.findReassignCounts(List.of(ORDER_ID, ORDER_ID + 1));

    assertThat(counts).containsEntry(ORDER_ID, 3).containsEntry(ORDER_ID + 1, 0);
  }

  @Test
  @DisplayName("a null SUM is read as zero rather than propagating null")
  void nullCountBecomesZero() {
    when(repository.findReassignCounts(anyList()))
        .thenReturn(List.<Object[]>of(new Object[] {BigInteger.valueOf(ORDER_ID), null}));

    assertThat(reader.findReassignCounts(List.of(ORDER_ID))).containsEntry(ORDER_ID, 0);
  }

  @Test
  @DisplayName("a row with a null order id is skipped, not mapped to a null key")
  void nullOrderIdRowIsSkipped() {
    when(repository.findReassignCounts(anyList()))
        .thenReturn(List.<Object[]>of(new Object[] {null, BigDecimal.ONE}));

    assertThat(reader.findReassignCounts(List.of(ORDER_ID))).isEmpty();
  }

  @Test
  @DisplayName("accepted distribution ids narrow from BigInteger")
  void acceptedIdsNarrowFromBigInteger() {
    when(repository.findAcceptedDistributionOrderIds(anyList()))
        .thenReturn(List.of(BigInteger.valueOf(ORDER_ID)));

    assertThat(reader.findAcceptedDistributionOrderIds(List.of(ORDER_ID)))
        .containsExactly(ORDER_ID);
  }

  @Test
  @DisplayName("empty and null result sets come back as empty collections, never null")
  void emptyResultsAreEmptyCollections() {
    when(repository.findReassignCounts(anyList())).thenReturn(null);
    when(repository.findAcceptedDistributionOrderIds(anyList())).thenReturn(null);
    when(repository.findSlotTimes(anyList())).thenReturn(null);
    when(repository.findUnassignedOrdersForBookingDate(
            anyList(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(null);
    when(repository.findSpRejectedAfterCutoff(anyList(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(null);

    assertThat(reader.findReassignCounts(List.of(ORDER_ID))).isEmpty();
    assertThat(reader.findAcceptedDistributionOrderIds(List.of(ORDER_ID))).isEmpty();
    assertThat(reader.findSlotStartMillis(List.of(ORDER_ID))).isEmpty();
    assertThat(
            reader.findUnassignedOrders(
                AutoAssignScope.SAME_DAY,
                Collections.emptyList(),
                Date.valueOf(LocalDate.now()),
                null))
        .isEmpty();
    assertThat(reader.findSpRejectedAfterCutoff(List.of(ORDER_ID), null)).isEmpty();
  }

  @Test
  @DisplayName("slot re-read resolves a parseable label and nulls an unparseable one")
  void slotStartMillisResolvesAndTolerates() {
    when(repository.findSlotTimes(anyList()))
        .thenReturn(
            List.of(
                slotRow(ORDER_ID, Date.valueOf(LocalDate.of(2026, 8, 31)), "02:00 PM - 03:00 PM"),
                slotRow(ORDER_ID + 1, Date.valueOf(LocalDate.of(2026, 8, 31)), "whenever")));

    Map<Long, Long> byOrderId = reader.findSlotStartMillis(List.of(ORDER_ID, ORDER_ID + 1));

    assertThat(byOrderId.get(ORDER_ID)).isNotNull();
    // Present as a key so the caller can tell "re-read said unparseable" from "not re-read at all".
    assertThat(byOrderId).containsKey(ORDER_ID + 1);
    assertThat(byOrderId.get(ORDER_ID + 1)).isNull();
  }

  private static UnassignedOrderProjection slotRow(Long orderId, Date bookingDate, String slot) {
    return new UnassignedOrderProjection() {
      @Override
      public Long getOrderId() {
        return orderId;
      }

      @Override
      public Date getBookingDate() {
        return bookingDate;
      }

      @Override
      public String getBookingTime() {
        return slot;
      }

      @Override
      public Boolean getIsExpressJob() {
        return null; // this query never selects it -- see the interface javadoc
      }

      @Override
      public Timestamp getCreatedAt() {
        return null; // this query never selects it -- see the interface javadoc
      }
    };
  }

  // ================================================================
  // Scope picks the query -- the D-1 variant must not apply a lead-time floor
  // ================================================================

  @Test
  @DisplayName("SAME_DAY uses the lead-time-filtered query; NEXT_DAY uses the unfiltered one")
  void scopeSelectsTheCandidateQuery() {
    Date bookingDate = Date.valueOf(LocalDate.now());
    Timestamp minStart = Timestamp.valueOf(LocalDate.now().atStartOfDay().plusHours(1));
    when(repository.findUnassignedOrdersForBookingDate(anyList(), any(), any()))
        .thenReturn(Collections.emptyList());
    when(repository.findUnassignedOrdersForBookingDateWithoutLeadTime(anyList(), any()))
        .thenReturn(Collections.emptyList());

    reader.findUnassignedOrders(AutoAssignScope.SAME_DAY, List.of(1, 3), bookingDate, minStart);
    verify(repository).findUnassignedOrdersForBookingDate(List.of(1, 3), bookingDate, minStart);
    verify(repository, never()).findUnassignedOrdersForBookingDateWithoutLeadTime(anyList(), any());

    reset(repository);
    when(repository.findUnassignedOrdersForBookingDateWithoutLeadTime(anyList(), any()))
        .thenReturn(Collections.emptyList());

    reader.findUnassignedOrders(AutoAssignScope.NEXT_DAY, List.of(1, 3), bookingDate, minStart);
    // The minStart argument is accepted but deliberately unused for NEXT_DAY -- cron's DAO wraps
    // that
    // restriction in `if (bookingDate == today)`, so tomorrow's orders were never filtered by it.
    verify(repository)
        .findUnassignedOrdersForBookingDateWithoutLeadTime(List.of(1, 3), bookingDate);
    verify(repository, never()).findUnassignedOrdersForBookingDate(anyList(), any(), any());
  }

  @Test
  @DisplayName("the D-1 bypass ids narrow from whatever numeric type the driver returns")
  void spRejectedIdsNarrowFromNumber() {
    Timestamp cutoff = Timestamp.valueOf(LocalDate.now().atStartOfDay().plusHours(15));
    when(repository.findSpRejectedAfterCutoff(anyList(), any()))
        .thenReturn(List.of(BigInteger.valueOf(ORDER_ID), BigInteger.valueOf(ORDER_ID + 1)));

    assertThat(reader.findSpRejectedAfterCutoff(List.of(ORDER_ID, ORDER_ID + 1), cutoff))
        .containsExactly(ORDER_ID, ORDER_ID + 1);
  }
}
