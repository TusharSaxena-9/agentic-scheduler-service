package com.yesmadam.schedulerservice.service.interfaces;

import java.util.List;
import java.util.Map;

import com.yesmadam.persistence.SPRegistration;
import com.yesmadam.schedulerservice.jobs.roseorder.model.SpOverallRatingProjection;

public interface SpService {

  List<Integer> findAllActiveSpIds();

  Map<Integer, Long> countCompletedOrdersCountForSpIDs(List<Integer> spIds);

  Map<Integer, SpOverallRatingProjection> spAvgRatingForLastOrders(
      List<Integer> spIds, int maxRatingCount);

  Map<Integer, Long> jobAssignedCount(List<Integer> spIds, int days);

  Map<Integer, Long> jobCompletedCountPerSP(List<Integer> spIds, int days);

  Map<Integer, Long> jobsRejectedCount(List<Integer> spIds, int days);

  Map<Integer, Long> jobsMarkedDefaulterCount(List<Integer> spIds, int days);

  Map<Integer, Long> jobsCancelButJobSuccessCount(List<Integer> spIds, int days);

  Map<Integer, Long> jobsImpactedBySPLeaveCount(List<Integer> spIds, int days);

  SPRegistration getReferenceForSpId(Integer sp);
}
