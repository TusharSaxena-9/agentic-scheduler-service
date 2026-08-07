package com.yesmadam.schedulerservice.jobs.crontiming.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yesmadam.schedulerservice.jobs.crontiming.model.CronTiming;
import com.yesmadam.schedulerservice.jobs.crontiming.repository.CronTimingRepository;

@ExtendWith(MockitoExtension.class)
class CronTimingServiceTest {

  @Mock CronTimingRepository cronTimingRepository;
  @InjectMocks CronTimingService cronTimingService;

  // ================================================================
  // saveCronStart
  // ================================================================

  @Test
  void saveCronStart_shouldSaveWithCorrectFields() {
    CronTiming saved = new CronTiming();
    saved.setId(1L);
    when(cronTimingRepository.save(any(CronTiming.class))).thenReturn(saved);

    Long id =
        cronTimingService.saveCronStart(
            "PackageExpiryWorkflow", "run", "Execute every day 12:10 AM");

    assertThat(id).isEqualTo(1L);

    ArgumentCaptor<CronTiming> captor = ArgumentCaptor.forClass(CronTiming.class);
    verify(cronTimingRepository).save(captor.capture());

    CronTiming captured = captor.getValue();
    assertThat(captured.getJavaFileName()).isEqualTo("PackageExpiryWorkflow");
    assertThat(captured.getMethodName()).isEqualTo("run");
    assertThat(captured.getRemark()).isEqualTo("Execute every day 12:10 AM");
    assertThat(captured.getCronStartAt()).isNotNull();
    assertThat(captured.getCreatedAt()).isNotNull();
  }

  @Test
  void saveCronStart_shouldReturnGeneratedId() {
    CronTiming saved = new CronTiming();
    saved.setId(42L);
    when(cronTimingRepository.save(any())).thenReturn(saved);

    Long id = cronTimingService.saveCronStart("file", "method", "remark");

    assertThat(id).isEqualTo(42L);
  }

  // ================================================================
  // saveCronEnd
  // ================================================================

  @Test
  void saveCronEnd_shouldUpdateCronEndAt() {
    CronTiming existing = new CronTiming();
    existing.setId(1L);
    when(cronTimingRepository.findById(1L)).thenReturn(Optional.of(existing));

    cronTimingService.saveCronEnd(1L);

    ArgumentCaptor<CronTiming> captor = ArgumentCaptor.forClass(CronTiming.class);
    verify(cronTimingRepository).save(captor.capture());

    assertThat(captor.getValue().getCronEndAt()).isNotNull();
  }

  @Test
  void saveCronEnd_shouldDoNothingIfIdNotFound() {
    when(cronTimingRepository.findById(999L)).thenReturn(Optional.empty());

    cronTimingService.saveCronEnd(999L);

    verify(cronTimingRepository, never()).save(any());
  }
}
