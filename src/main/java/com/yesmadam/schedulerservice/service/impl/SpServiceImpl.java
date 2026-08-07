package com.yesmadam.schedulerservice.service.impl;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.validation.constraints.NotNull;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.enumu.OrderStatus;
import com.yesmadam.persistence.SPRegistration;
import com.yesmadam.schedulerservice.jobs.roseorder.model.SpOverallRatingProjection;
import com.yesmadam.schedulerservice.repository.interfaces.SpRepository;
import com.yesmadam.schedulerservice.service.interfaces.SpService;

@Service
public class SpServiceImpl implements SpService {

  private final SpRepository spRepository;
  private final List<Integer> COMPLETED_ORDER_CODES =
      List.of(OrderStatus.ORDER_COMPLETED.getStatus(), OrderStatus.ORDER_FEEDBACK_DONE.getStatus());

  public SpServiceImpl(SpRepository spRepository) {
    this.spRepository = spRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Integer> findAllActiveSpIds() {
    List<Integer> spIds = spRepository.findIdsByStatus(true);
    return spIds == null ? new ArrayList<>() : spIds;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Integer, Long> countCompletedOrdersCountForSpIDs(List<Integer> spIds) {
    List<Object[]> data =
        spRepository.countCompletedOrdersCountForSpIDs(spIds, COMPLETED_ORDER_CODES);
    return data.stream()
        .collect(Collectors.toMap(r -> ((Number) r[0]).intValue(), r -> ((Number) r[1]).longValue()));
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Integer, SpOverallRatingProjection> spAvgRatingForLastOrders(
      List<Integer> spIds, int maxRatingCount) {
    if (spIds == null || spIds.isEmpty()) {
      return Collections.emptyMap();
    }

    List<SpOverallRatingProjection> avgSpRating =
        spRepository.spAvgRatingForLastOrders(spIds, maxRatingCount);

    return Optional.ofNullable(avgSpRating).orElseGet(Collections::emptyList).stream()
        .filter(Objects::nonNull)
        .filter(r -> r.getSpId() != null)
        .collect(
            Collectors.toMap(
                SpOverallRatingProjection::getSpId, Function.identity(), (r1, r2) -> r2));
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Integer, Long> jobAssignedCount(@NotNull List<Integer> spIds, int days) {
    List<Object[]> data = spRepository.jobAssignedCountPerSP(spIds, days);
    return data.stream()
        .collect(
            Collectors.toMap(
                r -> ((Number) r[0]).intValue(), r -> ((Number) r[1]).longValue(), (r1, r2) -> r2));
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Integer, Long> jobCompletedCountPerSP(@NotNull List<Integer> spIds, int days) {
    List<Object[]> data = spRepository.jobCompletedCountPerSP(spIds, days, COMPLETED_ORDER_CODES);
    return data.stream()
        .collect(
            Collectors.toMap(
                r -> ((Number) r[0]).intValue(), r -> ((Number) r[1]).longValue(), (r1, r2) -> r2));
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Integer, Long> jobsRejectedCount(@NotNull List<Integer> spIds, int days) {
    List<Object[]> data = spRepository.jobsRejectedCountPerSP(spIds, days);
    return data.stream()
        .collect(
            Collectors.toMap(
                r -> ((Number) r[0]).intValue(), r -> ((Number) r[1]).longValue(), (r1, r2) -> r2));
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Integer, Long> jobsMarkedDefaulterCount(@NotNull List<Integer> spIds, int days) {
    List<String> spIdsAsString =
        spIds.stream().map(String::valueOf).collect(Collectors.toList());
    List<Object[]> data = spRepository.jobsMarkedDefaulterCountPerSP(spIdsAsString, days);
    return data.stream()
        .collect(
            Collectors.toMap(
                r -> Integer.valueOf(r[0].toString()),
                r -> ((Number) r[1]).longValue(),
                (r1, r2) -> r2));
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Integer, Long> jobsCancelButJobSuccessCount(@NotNull List<Integer> spIds, int days) {
    List<Object[]> data = spRepository.jobsCancelButJobSuccessCountPerSP(spIds, days);
    return data.stream()
        .collect(
            Collectors.toMap(
                r -> ((Number) r[0]).intValue(), r -> ((Number) r[1]).longValue(), (r1, r2) -> r2));
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Integer, Long> jobsImpactedBySPLeaveCount(@NotNull List<Integer> spIds, int days) {
    List<Object[]> data = spRepository.jobsImpactedBySPLeaveCountPerSP(spIds, days);
    return data.stream()
        .collect(
            Collectors.toMap(
                r -> ((Number) r[0]).intValue(), r -> ((Number) r[1]).longValue(), (r1, r2) -> r2));
  }

  @Override
  public SPRegistration getReferenceForSpId(Integer sp) {
    return spRepository.getReferenceById(sp);
  }
}
