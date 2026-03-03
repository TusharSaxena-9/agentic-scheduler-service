package com.yesmadam.scheduler_service.crontiming.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yesmadam.scheduler_service.crontiming.service.CronTimingService;

@ExtendWith(MockitoExtension.class)
class CronTimingActivitiesImplTest {

  @Mock CronTimingService cronTimingService;
  @InjectMocks CronTimingActivitiesImpl activities;

  @Test
  void saveCronStart_shouldDelegateToServiceAndReturnId() {
    when(cronTimingService.saveCronStart("PackageExpiryWorkflow", "run", "remark")).thenReturn(1L);

    Long id = activities.saveCronStart("PackageExpiryWorkflow", "run", "remark");

    assertThat(id).isEqualTo(1L);
    verify(cronTimingService).saveCronStart("PackageExpiryWorkflow", "run", "remark");
  }

  @Test
  void saveCronEnd_shouldDelegateToService() {
    activities.saveCronEnd(1L);
    verify(cronTimingService).saveCronEnd(1L);
  }
}
