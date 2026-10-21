package com.yesmadam.schedulerservice.jobs.leavewalletaccrual.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yesmadam.schedulerservice.jobs.leavewalletaccrual.model.LeaveWalletAccrualRunContext;
import com.yesmadam.schedulerservice.jobs.leavewalletaccrual.service.LeaveWalletAccrualService;

@ExtendWith(MockitoExtension.class)
class LeaveWalletAccrualActivitiesImplTest {

  @Mock LeaveWalletAccrualService leaveWalletAccrualService;
  @InjectMocks LeaveWalletAccrualActivitiesImpl activities;

  @Test
  void fetchLeaveWalletAccrualRunContext_shouldDelegateToService() {
    LeaveWalletAccrualRunContext context =
        new LeaveWalletAccrualRunContext(
            List.of(1, 2), List.of(2), new BigDecimal("6"), new BigDecimal("1"));
    when(leaveWalletAccrualService.fetchRunContext()).thenReturn(context);

    LeaveWalletAccrualRunContext result = activities.fetchLeaveWalletAccrualRunContext();

    assertThat(result).isEqualTo(context);
    verify(leaveWalletAccrualService).fetchRunContext();
  }

  @Test
  void accrueMonthlyLeaveChunk_shouldDelegateToService() {
    when(leaveWalletAccrualService.accrueMonthlyLeaveChunk(
            List.of(1, 2), List.of(2), new BigDecimal("6"), new BigDecimal("1")))
        .thenReturn(2);

    int result =
        activities.accrueMonthlyLeaveChunk(
            List.of(1, 2), List.of(2), new BigDecimal("6"), new BigDecimal("1"));

    assertThat(result).isEqualTo(2);
    verify(leaveWalletAccrualService)
        .accrueMonthlyLeaveChunk(List.of(1, 2), List.of(2), new BigDecimal("6"), new BigDecimal("1"));
  }
}
