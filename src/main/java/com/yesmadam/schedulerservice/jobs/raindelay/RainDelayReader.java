package com.yesmadam.schedulerservice.jobs.raindelay;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every read this job performs, on its own bean so {@code readOnly = true} crosses the Spring proxy
 * and actually reaches the reader pool. A self-call would silently run on the writer — which is why
 * this is not folded into {@link RainDelayService}.
 *
 * <p>Both queries are pinned to {@code order_status_code = 3} inside the SQL — see {@link
 * RainDelayOrderRepository}.
 */
@Service
public class RainDelayReader {

  private final RainDelayOrderRepository orderRepository;

  public RainDelayReader(RainDelayOrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  /**
   * The {@code booking_date} bounds are a day wider than the window on each side — a coarse index
   * pre-filter, not a correctness filter. See {@link RainDelayOrderRepository}.
   *
   * @param hubIds from the {@code rainHub} settings row; must be non-empty
   */
  @Transactional(readOnly = true)
  public List<RainHubCandidateProjection> findCandidateHubs(
      List<Integer> hubIds, LocalDateTime windowStart, LocalDateTime windowEnd) {
    return orderRepository.findRainCheckHubsWithImminentBookings(
        hubIds,
        Date.valueOf(windowStart.toLocalDate().minusDays(1)),
        Date.valueOf(windowEnd.toLocalDate().plusDays(1)),
        Timestamp.valueOf(windowStart),
        Timestamp.valueOf(windowEnd));
  }

  /** @param rainyHubIds hubs whose weather came back rainy; must be non-empty */
  @Transactional(readOnly = true)
  public List<RainDelayTargetProjection> findNotificationTargets(
      List<Integer> rainyHubIds, LocalDateTime windowStart, LocalDateTime windowEnd) {
    return orderRepository.findNotificationTargets(
        rainyHubIds,
        Date.valueOf(windowStart.toLocalDate().minusDays(1)),
        Date.valueOf(windowEnd.toLocalDate().plusDays(1)),
        Timestamp.valueOf(windowStart),
        Timestamp.valueOf(windowEnd));
  }
}
