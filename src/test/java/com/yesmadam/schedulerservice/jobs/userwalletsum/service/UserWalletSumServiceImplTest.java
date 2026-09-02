package com.yesmadam.schedulerservice.jobs.userwalletsum.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yesmadam.schedulerservice.jobs.userwalletsum.model.DailyCustomerBalance;
import com.yesmadam.schedulerservice.jobs.userwalletsum.model.UserWalletSumReport;
import com.yesmadam.schedulerservice.jobs.userwalletsum.projections.UserWalletSumProjection;
import com.yesmadam.schedulerservice.jobs.userwalletsum.repository.DailyCustomerBalanceRepository;
import com.yesmadam.schedulerservice.jobs.userwalletsum.repository.UserWalletSumRepository;

@ExtendWith(MockitoExtension.class)
class UserWalletSumServiceImplTest {

  @Mock UserWalletSumRepository userWalletSumRepository;
  @Mock DailyCustomerBalanceRepository dailyCustomerBalanceRepository;
  @InjectMocks UserWalletSumServiceImpl service;

  @Test
  void computeWalletSum_shouldMapTheProjectionOntoTheReport() {
    when(userWalletSumRepository.sumAllWallets()).thenReturn(projection(1_000L, 500L));

    UserWalletSumReport report = service.computeWalletSum();

    assertThat(report.getMainWalletSum()).isEqualTo(1_000L);
    assertThat(report.getSeemlessWalletSum()).isEqualTo(500L);
  }

  @Test
  void computeWalletSum_shouldReturnNullSumsWhenTheTableIsEmpty() {
    when(userWalletSumRepository.sumAllWallets()).thenReturn(null);

    UserWalletSumReport report = service.computeWalletSum();

    assertThat(report.getMainWalletSum()).isNull();
    assertThat(report.getSeemlessWalletSum()).isNull();
  }

  @Test
  void saveDailyBalance_shouldPersistTheSumsWithATimestamp() {
    UserWalletSumReport report = new UserWalletSumReport(1_000L, 500L);

    service.saveDailyBalance(report);

    ArgumentCaptor<DailyCustomerBalance> captor = ArgumentCaptor.forClass(DailyCustomerBalance.class);
    verify(dailyCustomerBalanceRepository).save(captor.capture());
    DailyCustomerBalance saved = captor.getValue();
    assertThat(saved.getMainWalletSum()).isEqualTo(1_000L);
    assertThat(saved.getSeemlessWalletSum()).isEqualTo(500L);
    assertThat(saved.getCreatedAt()).isNotNull();
  }

  private UserWalletSumProjection projection(Long mainWalletSum, Long seemlessWalletSum) {
    return new UserWalletSumProjection() {
      @Override
      public Long getMainWalletSum() {
        return mainWalletSum;
      }

      @Override
      public Long getSeemlessWalletSum() {
        return seemlessWalletSum;
      }
    };
  }
}
