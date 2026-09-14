package com.yesmadam.schedulerservice.jobs.spproductordersync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yesmadam.schedulerservice.jobs.spproductordersync.repository.SpUnavailableProductSyncRepository;

@ExtendWith(MockitoExtension.class)
class SpProductOrderSyncServiceImplTest {

  @Mock SpUnavailableProductSyncRepository repository;
  @InjectMocks SpProductOrderSyncServiceImpl service;

  // ================================================================
  // incrementInventoryProductsPurchasedForDelivered
  // ================================================================

  @Test
  void incrementInventory_shouldReturnRowsUpdatedByRepository() {
    when(repository.incrementInventoryProductsPurchasedForDelivered()).thenReturn(7);

    int result = service.incrementInventoryProductsPurchasedForDelivered();

    assertThat(result).isEqualTo(7);
    verify(repository).incrementInventoryProductsPurchasedForDelivered();
  }

  @Test
  void incrementInventory_shouldReturnZeroWhenNothingMatches() {
    when(repository.incrementInventoryProductsPurchasedForDelivered()).thenReturn(0);

    assertThat(service.incrementInventoryProductsPurchasedForDelivered()).isZero();
  }

  // ================================================================
  // syncDeliveredSpUnavailableProducts
  // ================================================================

  @Test
  void syncDeliveredProducts_shouldReturnRowsUpdatedByRepository() {
    when(repository.syncDeliveredSpUnavailableProducts()).thenReturn(12);

    int result = service.syncDeliveredSpUnavailableProducts();

    assertThat(result).isEqualTo(12);
    verify(repository).syncDeliveredSpUnavailableProducts();
  }

  @Test
  void syncDeliveredProducts_shouldReturnZeroWhenNothingMatches() {
    when(repository.syncDeliveredSpUnavailableProducts()).thenReturn(0);

    assertThat(service.syncDeliveredSpUnavailableProducts()).isZero();
  }
}
