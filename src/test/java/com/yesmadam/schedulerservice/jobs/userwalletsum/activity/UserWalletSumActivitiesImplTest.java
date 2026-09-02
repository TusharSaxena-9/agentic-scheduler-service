package com.yesmadam.schedulerservice.jobs.userwalletsum.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yesmadam.schedulerservice.jobs.userwalletsum.model.UserWalletSumReport;
import com.yesmadam.schedulerservice.jobs.userwalletsum.service.UserWalletSumService;

@ExtendWith(MockitoExtension.class)
class UserWalletSumActivitiesImplTest {

  @Mock UserWalletSumService userWalletSumService;
  @InjectMocks UserWalletSumActivitiesImpl activities;

  @Test
  void computeWalletSum_shouldDelegateToService() {
    UserWalletSumReport report = new UserWalletSumReport(1_000L, 500L);
    when(userWalletSumService.computeWalletSum()).thenReturn(report);

    UserWalletSumReport result = activities.computeWalletSum();

    assertThat(result).isEqualTo(report);
  }

  @Test
  void saveDailyBalance_shouldDelegateToService() {
    UserWalletSumReport report = new UserWalletSumReport(1_000L, 500L);

    activities.saveDailyBalance(report);

    verify(userWalletSumService).saveDailyBalance(report);
  }
}
