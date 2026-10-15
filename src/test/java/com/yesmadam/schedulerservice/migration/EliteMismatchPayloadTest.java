package com.yesmadam.schedulerservice.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncAddress;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncChunkResult;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncRunContext;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncOrder;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncSapSeries;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncSapSession;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncServiceHub;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncSubscription;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncUser;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncYmElite;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository.EliteMismatchSyncAddressRepository;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository.EliteMismatchSyncOrderRepository;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository.EliteMismatchSyncSapMappingRepository;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository.EliteMismatchSyncSapSeriesRepository;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository.EliteMismatchSyncSapSessionRepository;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository.EliteMismatchSyncServiceHubRepository;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository.EliteMismatchSyncSubscriptionRepository;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository.EliteMismatchSyncUserRepository;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository.EliteMismatchSyncYmEliteRepository;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository.SapDocEntriesRepository;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.service.EliteMismatchSyncService;

/** Payload parity for job 8 (elite mismatch). */
class EliteMismatchPayloadTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static Object[] argsFor(Class<?> type, boolean realRestTemplate) {
    Constructor<?> ctor = type.getDeclaredConstructors()[0];
    ctor.setAccessible(true);
    Class<?>[] types = ctor.getParameterTypes();
    Object[] args = new Object[types.length];
    for (int i = 0; i < types.length; i++) {
      args[i] =
          realRestTemplate && types[i].equals(RestTemplate.class)
              ? new RestTemplate()
              : Mockito.mock(types[i]);
    }
    return args;
  }

  private static <T> T instantiate(Class<T> type, Object[] args) throws Exception {
    Constructor<?> ctor = type.getDeclaredConstructors()[0];
    ctor.setAccessible(true);
    return type.cast(ctor.newInstance(args));
  }

  @SuppressWarnings("unchecked")
  private static <R> R repo(Object[] args, Class<R> type) {
    return (R) Arrays.stream(args).filter(type::isInstance).findFirst().orElseThrow();
  }

  @Nested
  class EliteMismatch {

    private static final int SUBSCRIPTION_ROW_ID = 4141;
    private static final long CUSTOMER_ID = 9100L;

    private SapStubServer sap;
    private EliteMismatchSyncService service;
    private Object[] args;

    @BeforeEach
    void setUp() throws Exception {
      sap =
          new SapStubServer()
              .respondTo("/BusinessPartners", "{\"CardCode\":\"CUST-9100\"}")
              .respondTo("/Invoices", "{\"DocEntry\":6200,\"CardCode\":\"CUST-9100\"}")
              .respondByDefault("{\"DocEntry\":6300}");
      args = argsFor(EliteMismatchSyncService.class, false);
      service = instantiate(EliteMismatchSyncService.class, args);
      ReflectionTestUtils.setField(service, "cinntraBaseUrl", sap.baseUrl());
      ReflectionTestUtils.setField(service, "cinntraKey", "k");
      ReflectionTestUtils.setField(service, "cinntraUserId", "u");
      wire();
    }

    @AfterEach
    void tearDown() {
      sap.close();
    }

    private EliteMismatchSyncSubscription subscription(Integer discountedFee) {
      EliteMismatchSyncSubscription s = new EliteMismatchSyncSubscription();
      s.setId(SUBSCRIPTION_ROW_ID);
      s.setCustomerId(CUSTOMER_ID);
      s.setSubscriptionId(7);
      s.setDiscountedFee(discountedFee);
      s.setTxnid("ELITE-TXN-1");
      s.setCreatedAt(Timestamp.valueOf(LocalDateTime.of(2026, 6, 10, 6, 0)));
      return s;
    }

    private void wire() {
      EliteMismatchSyncUser user = new EliteMismatchSyncUser();
      user.setUserId(CUSTOMER_ID);
      user.setName("Elite Customer");

      EliteMismatchSyncAddress address = new EliteMismatchSyncAddress();
      address.setUserId(CUSTOMER_ID);
      address.setAddress("5 Park Lane");
      address.setAddress1("Block C");
      address.setPincode("110001");
      address.setCity("Delhi");
      address.setHubId(2);
      address.setIsDefault(true);

      EliteMismatchSyncServiceHub hub = new EliteMismatchSyncServiceHub();
      hub.setId(2);
      hub.setHub("DELHI");
      hub.setState("Delhi");
      hub.setSapWhsCode("DL-DL-1");
      hub.setSapBplId(1);
      hub.setCgst(9.0f);
      hub.setSgst(9.0f);

      EliteMismatchSyncSapSeries series = new EliteMismatchSyncSapSeries();
      series.setSapWhsCode("DL-DL-1");
      series.setArInvoiceSeries(321);
      series.setIncomingPaymentSeries(654);

      EliteMismatchSyncSapSession session = new EliteMismatchSyncSapSession();
      session.setId(1);
      session.setSessionId("SESSION-1");

      EliteMismatchSyncYmElite plan = new EliteMismatchSyncYmElite();
      plan.setId(7);
      plan.setAmount(1499);

      lenient()
          .when(
              repo(args, EliteMismatchSyncSubscriptionRepository.class)
                  .findById(SUBSCRIPTION_ROW_ID))
          .thenReturn(Optional.of(subscription(null)));
      // Bulk load answers from the per-id stubs, so a test that re-stubs findById changes both.
      EliteMismatchSyncSubscriptionRepository subscriptions =
          repo(args, EliteMismatchSyncSubscriptionRepository.class);
      lenient()
          .when(subscriptions.findAllById(any()))
          .thenAnswer(
              inv -> {
                List<EliteMismatchSyncSubscription> found = new java.util.ArrayList<>();
                for (Integer id : inv.<Iterable<Integer>>getArgument(0)) {
                  subscriptions.findById(id).ifPresent(found::add);
                }
                return found;
              });
      lenient()
          .when(repo(args, EliteMismatchSyncUserRepository.class).findById(CUSTOMER_ID))
          .thenReturn(Optional.of(user));
      lenient()
          .when(
              repo(args, EliteMismatchSyncAddressRepository.class)
                  .findFirstByUserIdAndIsDefault(CUSTOMER_ID, true))
          .thenReturn(Optional.of(address));
      lenient()
          .when(repo(args, EliteMismatchSyncServiceHubRepository.class).findById(2))
          .thenReturn(Optional.of(hub));
      lenient()
          .when(repo(args, EliteMismatchSyncSapSeriesRepository.class).findBySapWhsCode("DL-DL-1"))
          .thenReturn(Optional.of(series));
      lenient()
          .when(repo(args, EliteMismatchSyncYmEliteRepository.class).findById(7))
          .thenReturn(Optional.of(plan));
      lenient()
          .when(
              repo(args, EliteMismatchSyncOrderRepository.class)
                  .findByUseridAndEliteFeesIsNotNull(any()))
          .thenReturn(List.of());
      lenient()
          .when(
              repo(args, EliteMismatchSyncSapMappingRepository.class)
                  .findFirstBySpIdAndTypeCode(any(), any()))
          .thenReturn(Optional.empty());
      lenient()
          .when(repo(args, EliteMismatchSyncSapSessionRepository.class).findById(1))
          .thenReturn(Optional.of(session));
    }

    @Test
    void bulkLoadKeepsTheSelectionOrderSoTheFirstSubscriptionIsTheOneTrackingPicks() throws Exception {
      EliteMismatchSyncSubscription newer = subscription(null);
      EliteMismatchSyncSubscription older = subscription(null);
      older.setId(SUBSCRIPTION_ROW_ID + 1);
      older.setCustomerId(CUSTOMER_ID + 1);
      older.setSubscriptionId(8);
      // The database hands rows back in its own order, not the createdAt DESC order of the ids.
      org.mockito.Mockito.doReturn(List.of(older, newer))
          .when(repo(args, EliteMismatchSyncSubscriptionRepository.class))
          .findAllById(any());

      EliteMismatchSyncRunContext context =
          service.buildRunContext(List.of(SUBSCRIPTION_ROW_ID, SUBSCRIPTION_ROW_ID + 1, 999_999));

      assertThat(context.getSubscriptionIds()).containsExactly(SUBSCRIPTION_ROW_ID, SUBSCRIPTION_ROW_ID + 1);
      assertThat(context.getFallbackPlanAmount()).isEqualTo(1499);
      org.mockito.Mockito.verify(repo(args, EliteMismatchSyncOrderRepository.class))
          .findByUseridAndEliteFeesIsNotNull((int) CUSTOMER_ID);
      org.mockito.Mockito.verify(repo(args, EliteMismatchSyncSubscriptionRepository.class), org.mockito.Mockito.never())
          .findById(any());
    }

    @Test
    void retriedChunkSkipsASubscriptionAnEarlierAttemptAlreadyPosted() throws Exception {
      lenient()
          .when(repo(args, SapDocEntriesRepository.class).existsByOrderId(SUBSCRIPTION_ROW_ID))
          .thenReturn(true);

      EliteMismatchSyncChunkResult result = service.syncEliteMismatches(List.of(SUBSCRIPTION_ROW_ID));

      assertThat(sap.calls()).isEmpty();
      assertThat(result.getAlreadySynced()).isEqualTo(1);
      assertThat(result.getSucceeded()).isZero();
    }

    @Test
    void chunkReportsProgressPerSubscriptionAndReturnsOnlyCounts() throws Exception {
      List<Integer> reported = new java.util.ArrayList<>();

      EliteMismatchSyncChunkResult result =
          service.syncChunk(List.of(SUBSCRIPTION_ROW_ID, 999_999), java.util.Map.of(), 1499, reported::add);

      assertThat(reported).containsExactly(1, 2);
      assertThat(result.getSucceeded()).isEqualTo(1);
      assertThat(result.getNotFound()).isEqualTo(1);
    }

    @Test
    void sendsNothingToSapWhenThereIsNoSessionRow() throws Exception {
      lenient()
          .when(repo(args, EliteMismatchSyncSapSessionRepository.class).findById(1))
          .thenReturn(Optional.empty());

      service.syncEliteMismatches(List.of(SUBSCRIPTION_ROW_ID));

      assertThat(sap.calls()).isEmpty();
    }

    @Test
    void raisesAnEliteInvoiceForThePlanAmountWhenNoOtherFeeIsKnown() throws Exception {
      service.syncEliteMismatches(List.of(SUBSCRIPTION_ROW_ID));

      JsonNode invoice = MAPPER.readTree(sap.callTo("/Invoices").body());
      assertThat(invoice.get("Series").asInt()).isEqualTo(321);
      assertThat(invoice.get("DocumentLines").get(0).get("ItemCode").asText())
          .isEqualTo("SER000001");
      assertThat(invoice.get("DocumentLines").get(0).get("UnitPrice").asDouble())
          .as("falls back to the YmElite plan amount, 1499 gross, divided by 1.18")
          .isEqualTo(1499 / 1.18d, Offset.offset(0.01d));
    }

    @Test
    void prefersTheSubscriptionsDiscountedFeeOverThePlanAmount() throws Exception {
      when(repo(args, EliteMismatchSyncSubscriptionRepository.class).findById(SUBSCRIPTION_ROW_ID))
          .thenReturn(Optional.of(subscription(999)));

      service.syncEliteMismatches(List.of(SUBSCRIPTION_ROW_ID));

      assertThat(
              MAPPER
                  .readTree(sap.callTo("/Invoices").body())
                  .get("DocumentLines")
                  .get(0)
                  .get("UnitPrice")
                  .asDouble())
          .isEqualTo(999 / 1.18d, Offset.offset(0.01d));
    }

    @Test
    void prefersTheOrdersEliteFeeAboveEverythingElse() throws Exception {
      EliteMismatchSyncOrder order = new EliteMismatchSyncOrder();
      order.setUserid((int) CUSTOMER_ID);
      order.setEliteFees(777);
      order.setCustomerSubscriptionId(SUBSCRIPTION_ROW_ID);
      order.setCreatedAt(Timestamp.valueOf(LocalDateTime.of(2026, 6, 9, 10, 0)));
      when(repo(args, EliteMismatchSyncOrderRepository.class)
              .findByUseridAndEliteFeesIsNotNull(any()))
          .thenReturn(List.of(order));
      when(repo(args, EliteMismatchSyncSubscriptionRepository.class).findById(SUBSCRIPTION_ROW_ID))
          .thenReturn(Optional.of(subscription(999)));

      service.syncEliteMismatches(List.of(SUBSCRIPTION_ROW_ID));

      assertThat(
              MAPPER
                  .readTree(sap.callTo("/Invoices").body())
                  .get("DocumentLines")
                  .get(0)
                  .get("UnitPrice")
                  .asDouble())
          .as("order map fee wins over discounted fee and plan amount")
          .isEqualTo(777 / 1.18d, Offset.offset(0.01d));
    }

    @Test
    void followsTheInvoiceWithAnIncomingPayment() throws Exception {
      service.syncEliteMismatches(List.of(SUBSCRIPTION_ROW_ID));

      JsonNode payment = MAPPER.readTree(sap.callTo("/IncomingPayments").body());
      assertThat(payment.get("Series").asInt()).isEqualTo(654);
      assertThat(payment.get("CardCode").asText()).isEqualTo("CUST-9100");
    }
  }
}
