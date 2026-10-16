package com.yesmadam.schedulerservice.jobs.billingcheck.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yesmadam.schedulerservice.common.progress.ProgressReporter;
import com.yesmadam.schedulerservice.jobs.billingcheck.model.BillingCheckChunk;
import com.yesmadam.schedulerservice.jobs.billingcheck.model.BillingCheckOrder;
import com.yesmadam.schedulerservice.jobs.billingcheck.model.BillingCheckSendResult;
import com.yesmadam.schedulerservice.jobs.billingcheck.projections.BillingCheckOrderProjection;
import com.yesmadam.schedulerservice.jobs.billingcheck.whatsapp.BillingCheckSendException;
import com.yesmadam.schedulerservice.jobs.billingcheck.whatsapp.BillingCheckWhatsappService;

@ExtendWith(MockitoExtension.class)
class BillingCheckServiceTest {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  @Mock BillingCheckReader reader;
  @Mock BillingCheckWhatsappService whatsapp;

  BillingCheckProperties properties;
  BillingCheckService service;

  @BeforeEach
  void setUp() {
    properties = new BillingCheckProperties();
    properties.setTemplateName("feedback_addon_1");
    properties.setCategoryIds(List.of(5));
    properties.setChunkSize(3);
    service = new BillingCheckService(reader, whatsapp, properties);
  }

  // Window

  @Test
  void window_shouldCoverPreviousFivePmToTodayFivePm() {
    BillingCheckWindow w = BillingCheckWindow.forNominalFireTime(ist("2026-10-13T17:00:20"));

    assertThat(w.start().toInstant()).isEqualTo(zdt("2026-10-12T17:00:00").toInstant());
    assertThat(w.end().toInstant()).isEqualTo(zdt("2026-10-13T17:00:00").toInstant());
  }

  @Test
  void window_shouldBeIdenticalForALateCatchUpRunTheNextMorning() {
    BillingCheckWindow onTime = BillingCheckWindow.forNominalFireTime(ist("2026-10-13T17:01:00"));
    BillingCheckWindow lateRun = BillingCheckWindow.forNominalFireTime(ist("2026-10-14T02:00:00"));

    assertThat(lateRun.start()).isEqualTo(onTime.start());
    assertThat(lateRun.end()).isEqualTo(onTime.end());
  }

  @Test
  void window_shouldTileAcrossConsecutiveDaysWithNoGapOrOverlap() {
    BillingCheckWindow day1 = BillingCheckWindow.forNominalFireTime(ist("2026-10-13T17:00:10"));
    BillingCheckWindow day2 = BillingCheckWindow.forNominalFireTime(ist("2026-10-14T17:00:45"));

    assertThat(day2.start()).isEqualTo(day1.end());
  }

  @Test
  void window_shouldUsePreviousDayWhenEvaluatedBeforeTheCutoff() {
    BillingCheckWindow w = BillingCheckWindow.forNominalFireTime(ist("2026-10-13T16:59:00"));

    assertThat(w.end().toInstant()).isEqualTo(zdt("2026-10-12T17:00:00").toInstant());
  }

  // Message formatting

  @Test
  void servicesLine_shouldTruncateAtAServiceBoundaryWithAndMore() {
    String names = "Full Arms Waxing, Full Legs Waxing, Underarms Waxing, Cleanup, Pedicure, Manicure";

    String line = BillingCheckService.servicesLine(names);

    assertThat(line).endsWith(" and more");
    assertThat(line.replace(" and more", "").length()).isLessThanOrEqualTo(60);
    assertThat(line).doesNotContain("\n");
    assertThat(line).startsWith("Full Arms Waxing, Full Legs Waxing");
    assertThat(line.replace(" and more", "")).doesNotEndWith(",");
  }

  @Test
  void servicesLine_shouldLeaveShortListsAloneAndFlattenNewlines() {
    assertThat(BillingCheckService.servicesLine("Facial,\nCleanup")).isEqualTo("Facial, Cleanup");
    assertThat(BillingCheckService.servicesLine("")).isEqualTo("your booked services");
    assertThat(BillingCheckService.servicesLine(null)).isEqualTo("your booked services");
  }

  @Test
  void firstName_shouldTakeFirstTokenAndFallBackToThere() {
    assertThat(BillingCheckService.firstName("  Priya  Sharma ")).isEqualTo("Priya");
    assertThat(BillingCheckService.firstName(null)).isEqualTo("there");
    assertThat(BillingCheckService.firstName("   ")).isEqualTo("there");
  }

  @Test
  void finalAmount_shouldMatchAppServerFormulaPlusCodFee() {
    BillingCheckOrderProjection p = mock(BillingCheckOrderProjection.class);
    when(p.getTotal()).thenReturn(1000);
    when(p.getTip()).thenReturn(50);
    when(p.getConvenienceFee()).thenReturn(29.9); // longValue() truncates, as app-server does
    when(p.getTransportCharges()).thenReturn(40);
    when(p.getCodExtraFee()).thenReturn(9);
    when(p.getPrimeTimeCharge()).thenReturn(20);
    when(p.getLowCartFee()).thenReturn(10);
    when(p.getDisposable()).thenReturn(5);
    when(p.getCouponDiscount()).thenReturn(100);
    when(p.getWalletRedeemAmount()).thenReturn(200);
    when(p.getPackageDiscount()).thenReturn(30);
    when(p.getMypPackageDiscount()).thenReturn(0);
    when(p.getPrevCancellationFee()).thenReturn(60);
    when(p.getCancellationFeeDiscount()).thenReturn(10);
    when(p.getElBookingDiscount()).thenReturn(15);
    when(p.getCovidSelfTestAmount()).thenReturn(0);

    // 1000 + (50+29+40+9+0+20+10+5) - (100+200+30+0) + 60 - 10 - 15
    assertThat(BillingAmountCalculator.finalAmount(p)).isEqualTo(868L);
  }

  @Test
  void finalAmount_shouldTreatNullComponentsAsZero() {
    BillingCheckOrderProjection p = mock(BillingCheckOrderProjection.class);
    when(p.getTotal()).thenReturn(700);

    assertThat(BillingAmountCalculator.finalAmount(p)).isEqualTo(700L);
  }

  // fetchChunk

  @Test
  void fetchChunk_shouldBuildOrdersAndAdvanceTheCursorToTheLastOrderId() {
    stubRows(row(11, 0), row(12, 0));

    BillingCheckChunk chunk = service.fetchChunk(nominal(), 0L);

    assertThat(chunk.getOrders()).extracting(BillingCheckOrder::getOrderId).containsExactly(11L, 12L);
    assertThat(chunk.getNextCursor()).isEqualTo(12L);
    assertThat(chunk.isDone()).isTrue(); // 2 fetched < chunk size 3
  }

  @Test
  void fetchChunk_shouldNotBeDoneWhenAFullPageCameBack() {
    stubRows(row(1, 0), row(2, 0), row(3, 0));

    BillingCheckChunk chunk = service.fetchChunk(nominal(), 0L);

    assertThat(chunk.isDone()).isFalse();
    assertThat(chunk.getNextCursor()).isEqualTo(3L);
  }

  @Test
  void fetchChunk_shouldBeDoneAndKeepTheCursorWhenNothingQualifies() {
    stubRows();

    BillingCheckChunk chunk = service.fetchChunk(nominal(), 77L);

    assertThat(chunk.isDone()).isTrue();
    assertThat(chunk.getNextCursor()).isEqualTo(77L);
    assertThat(chunk.getOrders()).isEmpty();
  }

  @Test
  void fetchChunk_shouldPassTheTemplateVariablesInOrder() {
    BillingCheckOrderProjection p = row(1, 1499);
    when(p.getCustomerName()).thenReturn("Priya Sharma");
    when(p.getCategoryName()).thenReturn("Beauty Salon at Home");
    when(p.getServiceNames()).thenReturn("Facial, Cleanup");
    stubRows(p);

    BillingCheckChunk chunk = service.fetchChunk(nominal(), 0L);

    assertThat(chunk.getOrders().get(0).getBodyParams())
        .containsExactly("Priya", "Beauty Salon at Home", "1499", "Facial, Cleanup");
  }

  @Test
  void fetchChunk_shouldQueryTheWindowDerivedFromTheNominalTimeNotTheClock() {
    stubRows();

    service.fetchChunk(ist("2026-10-14T02:00:00"), 0L); // a late catch-up run

    org.mockito.ArgumentCaptor<BillingCheckWindow> w =
        org.mockito.ArgumentCaptor.forClass(BillingCheckWindow.class);
    verify(reader).findEligibleOrders(any(), eq(List.of(5)), w.capture(), eq(0L), eq(3));
    assertThat(w.getValue().end().toInstant()).isEqualTo(zdt("2026-10-13T17:00:00").toInstant());
  }

  @Test
  void chunk_shouldBeBoundedByTheChunkSizeNotTheTable() {
    // Rule 9: a page never exceeds the chunk size.
    stubRows(row(1, 0), row(2, 0), row(3, 0));

    assertThat(service.fetchChunk(nominal(), 0L).getOrders()).hasSizeLessThanOrEqualTo(3);
  }

  // sendChunk

  @Test
  void sendChunk_shouldSendEveryOrderInThePage() {
    BillingCheckChunk chunk = chunkOf(order(1), order(2));

    BillingCheckSendResult r = service.sendChunk(chunk, ProgressReporter.noOp());

    assertThat(r.getSent()).isEqualTo(2);
    assertThat(r.getFailed()).isZero();
    verify(whatsapp).send(eq("9999900001"), eq("feedback_addon_1"), any(), eq(1L));
    verify(whatsapp).send(eq("9999900002"), eq("feedback_addon_1"), any(), eq(2L));
  }

  @Test
  void sendChunk_shouldKeepGoingAfterAMidRunSendFailure() {
    // Orders either side of a failure are neither lost nor sent twice.
    org.mockito.Mockito.lenient()
        .doThrow(new BillingCheckSendException("timeout", null))
        .when(whatsapp)
        .send(anyString(), anyString(), any(), eq(2L));

    BillingCheckSendResult r = service.sendChunk(chunkOf(order(1), order(2), order(3)), ProgressReporter.noOp());

    assertThat(r.getSent()).isEqualTo(2);
    assertThat(r.getFailed()).isEqualTo(1);
    verify(whatsapp, times(1)).send(anyString(), anyString(), any(), eq(1L));
    verify(whatsapp, times(1)).send(anyString(), anyString(), any(), eq(3L));
  }

  @Test
  void sendChunk_shouldLetACancellationFromTheHeartbeatEscape() {
    ProgressReporter cancelled =
        completed -> {
          throw new IllegalStateException("activity cancelled");
        };

    assertThatThrownBy(() -> service.sendChunk(chunkOf(order(1), order(2)), cancelled))
        .isInstanceOf(IllegalStateException.class);

    verify(whatsapp, never()).send(anyString(), anyString(), any(), anyLong());
  }

  @Test
  void sendChunk_shouldDoNothingForAnEmptyPage() {
    BillingCheckSendResult r = service.sendChunk(chunkOf(), ProgressReporter.noOp());

    assertThat(r.getSent()).isZero();
    verify(whatsapp, never()).send(anyString(), anyString(), any(), anyLong());
  }

  // Startup validation

  @Test
  void properties_shouldFailStartupOnMissingTemplateOrCategories() {
    BillingCheckProperties p = new BillingCheckProperties();
    assertThatThrownBy(p::validate).hasMessageContaining("template-name");

    p.setTemplateName("feedback_addon_1");
    assertThatThrownBy(p::validate).hasMessageContaining("category-ids");

    p.setCategoryIds(List.of(5));
    p.setChunkSize(0);
    assertThatThrownBy(p::validate).hasMessageContaining("chunk-size");
  }

  // helpers

  private void stubRows(BillingCheckOrderProjection... rows) {
    when(reader.findEligibleOrders(any(), any(), any(), anyLong(), anyInt()))
        .thenReturn(List.of(rows));
  }

  private BillingCheckOrderProjection row(long orderId, int total) {
    BillingCheckOrderProjection p = mock(BillingCheckOrderProjection.class);
    org.mockito.Mockito.lenient().when(p.getOrderId()).thenReturn(orderId);
    org.mockito.Mockito.lenient().when(p.getMobile()).thenReturn(9999900000L + orderId);
    org.mockito.Mockito.lenient().when(p.getTotal()).thenReturn(total);
    return p;
  }

  private static BillingCheckOrder order(long orderId) {
    return new BillingCheckOrder(orderId, 9999900000L + orderId, List.of("Priya", "Beauty", "100", "Facial"));
  }

  private static BillingCheckChunk chunkOf(BillingCheckOrder... orders) {
    BillingCheckChunk c = new BillingCheckChunk();
    c.setOrders(List.of(orders));
    return c;
  }

  private static long nominal() {
    return ist("2026-10-13T17:00:30");
  }

  private static long ist(String local) {
    return zdt(local).toInstant().toEpochMilli();
  }

  private static ZonedDateTime zdt(String local) {
    return java.time.LocalDateTime.parse(local).atZone(IST);
  }
}
