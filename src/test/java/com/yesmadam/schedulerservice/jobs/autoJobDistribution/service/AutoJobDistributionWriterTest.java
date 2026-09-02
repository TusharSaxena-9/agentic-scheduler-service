package com.yesmadam.schedulerservice.jobs.autoJobDistribution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.JobDistribution;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.JobDistributionStatus;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository.DistributionPendingQueueBatchDao;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository.DistributionPendingQueueRepository;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository.JobDistributionRepository;

/**
 * The writer bean owns one branch worth pinning: ACCEPTED closes through a different statement from
 * every other terminal status, because it also records who accepted and for how much.
 *
 * <p>This used to be asserted indirectly through the service's tests. Now that the writes live on
 * their own bean — so that {@code @Transactional} actually applies rather than being bypassed by
 * self-invocation (rule 13) — the mapping belongs here, and the service's tests assert the
 * <em>decision</em> instead of the statement.
 */
@ExtendWith(MockitoExtension.class)
class AutoJobDistributionWriterTest {

  private static final long ROUND_ID = 77L;
  private static final long ORDER_ID = 5154904L;

  @Mock JobDistributionRepository jobDistributionRepository;
  @Mock DistributionPendingQueueRepository pendingQueueRepository;
  @Mock DistributionPendingQueueBatchDao pendingQueueBatchDao;

  @InjectMocks AutoJobDistributionWriter writer;

  private static JobDistribution round() {
    JobDistribution round = new JobDistribution();
    round.setId(ROUND_ID);
    round.setOrderId(ORDER_ID);
    return round;
  }

  @Test
  @DisplayName("ACCEPTED closes through closeAsAccepted, recording the SP and the amount")
  void acceptedUsesTheAcceptingStatement() {
    writer.close(round(), JobDistributionStatus.ACCEPTED, 12533, 200);

    verify(jobDistributionRepository).closeAsAccepted(eq(ROUND_ID), eq(12533), eq(200), any());
    verify(jobDistributionRepository, never()).closeWithStatus(any(), any(), any());
  }

  @Test
  @DisplayName("every other terminal status shares the one closeWithStatus statement")
  void otherStatusesShareOneStatement() {
    for (JobDistributionStatus status :
        List.of(
            JobDistributionStatus.CANCELLED,
            JobDistributionStatus.SUPERSEDED,
            JobDistributionStatus.EXPIRED,
            JobDistributionStatus.RESCHEDULED,
            JobDistributionStatus.FINAL_UNASSIGNED)) {
      writer.close(round(), status, null, null);
      verify(jobDistributionRepository).closeWithStatus(eq(ROUND_ID), eq(status.name()), any());
    }
    verify(jobDistributionRepository, never()).closeAsAccepted(any(), any(), any(), any());
  }

  @Test
  @DisplayName("a close always resolves the order's queue row in the same transaction")
  void closeAlsoResolvesTheQueueRow() {
    // These two belong together: a round closed without its queue row resolved leaves the order
    // looking mid-flight, which is a value the next pass acts on.
    writer.close(round(), JobDistributionStatus.CANCELLED, null, null);

    verify(pendingQueueRepository).markPendingResolved(eq(ORDER_ID), any());
  }

  @Test
  @DisplayName("enqueue delegates to the batch DAO and returns rows actually inserted")
  void enqueueReturnsInsertedCount() {
    // Lower than the input size whenever an order was already waiting — that gap is the unique key
    // doing its job, and the caller reports it.
    when(pendingQueueBatchDao.enqueuePending(anyList())).thenReturn(2);

    assertThat(writer.enqueuePending(List.of(1L, 2L, 3L))).isEqualTo(2);
  }
}
