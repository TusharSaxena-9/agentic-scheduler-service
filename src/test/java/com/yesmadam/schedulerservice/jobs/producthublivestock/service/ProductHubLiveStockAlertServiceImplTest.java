package com.yesmadam.schedulerservice.jobs.producthublivestock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.yesmadam.persistence.SettingsForEnalbeDisable;
import com.yesmadam.schedulerservice.common.mail.MailService;
import com.yesmadam.schedulerservice.common.whatsapp.service.WhatsappAlertService;
import com.yesmadam.schedulerservice.jobs.producthublivestock.enums.ProductHubLiveStockAlertConfig;
import com.yesmadam.schedulerservice.jobs.producthublivestock.model.LiveStockAlertReport;
import com.yesmadam.schedulerservice.jobs.producthublivestock.model.LiveStockAlertRow;
import com.yesmadam.schedulerservice.jobs.producthublivestock.projections.LiveStockAlertProjection;
import com.yesmadam.schedulerservice.jobs.producthublivestock.repository.LiveStockAlertRepository;
import com.yesmadam.schedulerservice.service.interfaces.SettingService;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductHubLiveStockAlertServiceImplTest {

  private static final String DETECTED_AT = "16th June, 2026 00:00:00";

  @Mock LiveStockAlertRepository liveStockAlertRepository;
  @Mock SettingService settingService;
  @Mock MailService mailService;
  @Mock WhatsappAlertService whatsappAlertService;
  @InjectMocks ProductHubLiveStockAlertServiceImpl service;

  private void givenConfig() {
    ReflectionTestUtils.setField(service, "mailFrom", "no-reply@yesmadam.com");
    ReflectionTestUtils.setField(
        service, "mailRecipients", List.of("amandeep.saini@yesmadam.com", "  ", ""));
  }

  private void givenSettings(String hubIds, String productIds) {
    when(settingService.findSettingWithKeys(any()))
        .thenReturn(
            Map.of(
                ProductHubLiveStockAlertConfig.HUB_IDS.key(), setting(hubIds),
                ProductHubLiveStockAlertConfig.PRODUCT_IDS.key(), setting(productIds)));
  }

  private static SettingsForEnalbeDisable setting(String value) {
    SettingsForEnalbeDisable s = new SettingsForEnalbeDisable();
    s.setValue(value);
    return s;
  }

  // ================================================================
  // fetchReport
  // ================================================================

  @Test
  void fetchReport_shouldSkipTheQueryWhenHubIdsAreNotConfigured() {
    givenSettings("", "5,7");

    LiveStockAlertReport report = service.fetchReport();

    assertThat(report.getRows()).isEmpty();
    verify(liveStockAlertRepository, never()).findOutOfStock(anyList(), anyList());
  }

  @Test
  void fetchReport_shouldSkipTheQueryWhenProductIdsAreNotConfigured() {
    givenSettings("1,2", "");

    LiveStockAlertReport report = service.fetchReport();

    assertThat(report.getRows()).isEmpty();
    verify(liveStockAlertRepository, never()).findOutOfStock(anyList(), anyList());
  }

  @Test
  void fetchReport_shouldMapProjectionRowsOntoTheReport() {
    givenSettings("1,2", "5,7");
    when(liveStockAlertRepository.findOutOfStock(List.of(1, 2), List.of(5, 7)))
        .thenReturn(List.of(projection(1, "Gurgaon Sector 52", 5, "Rica Wax")));

    LiveStockAlertReport report = service.fetchReport();

    assertThat(report.getRows()).hasSize(1);
    assertThat(report.getRows().get(0).getHubName()).isEqualTo("Gurgaon Sector 52");
    assertThat(report.getRows().get(0).getProductName()).isEqualTo("Rica Wax");
  }

  @Test
  void fetchReport_shouldCarryQueryTimingAndRowCount() {
    givenSettings("1", "5");
    when(liveStockAlertRepository.findOutOfStock(List.of(1), List.of(5)))
        .thenReturn(List.of(projection(1, "Gurgaon Sector 52", 5, "Rica Wax")));

    LiveStockAlertReport report = service.fetchReport();

    assertThat(report.getQueryDurationsMs()).containsKey("findOutOfStock");
    assertThat(report.getQueryRowCounts()).containsEntry("findOutOfStock", 1);
  }

  // ================================================================
  // mailReport
  // ================================================================

  @Test
  void mailReport_shouldNotMailAnEmptyReport() {
    givenConfig();

    service.mailReport(new LiveStockAlertReport(DETECTED_AT, List.of()));

    verify(mailService, never()).sendHtml(anyString(), anyList(), anyString(), anyString());
  }

  @Test
  void mailReport_shouldMailHubAndProductColumnsWithBlankRecipientsFiltered() {
    givenConfig();

    service.mailReport(
        new LiveStockAlertReport(
            DETECTED_AT, List.of(row(1, "Gurgaon Sector 52", 5, "Rica Wax"))));

    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<List<String>> to = ArgumentCaptor.forClass(List.class);
    verify(mailService).sendHtml(anyString(), to.capture(), anyString(), body.capture());

    assertThat(body.getValue()).contains("Gurgaon Sector 52").contains("Rica Wax")
        .contains("Product Hub").contains("Product Name");
    assertThat(to.getValue()).containsExactly("amandeep.saini@yesmadam.com");
  }

  @Test
  void mailReport_shouldPutOneRowPerHubWithProductsCommaSeparated() {
    givenConfig();

    service.mailReport(
        new LiveStockAlertReport(
            DETECTED_AT,
            List.of(
                row(1, "Noida", 5, "Acetone Spray Bottle"),
                row(1, "Noida", 6, "Cuticle Trimmer"),
                row(2, "Delhi Saket", 6, "Rica Wax"))));

    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(mailService).sendHtml(anyString(), anyList(), anyString(), body.capture());

    // one <tr> per hub, not per hub/product pair — two hubs here, so exactly two data rows.
    String html = body.getValue();
    assertThat(html).contains("Acetone Spray Bottle, Cuticle Trimmer");
    assertThat(countOccurrences(html, "<tr><td>")).isEqualTo(2);
  }

  // ================================================================
  // whatsappReport
  // ================================================================

  @Test
  void whatsappReport_shouldSkipAnEmptyReport() {
    service.whatsappReport(
        new LiveStockAlertReport(DETECTED_AT, List.of()), "inventory_oos", List.of("9999999999"));

    verify(whatsappAlertService, never()).sendWhatsappSms(anyString(), anyString(), anyList());
  }

  @Test
  void whatsappReport_shouldSkipWhenNoNumbersAreConfigured() {
    service.whatsappReport(
        new LiveStockAlertReport(DETECTED_AT, List.of(row(1, "Gurgaon Sector 52", 5, "Rica Wax"))),
        "inventory_oos",
        List.of("  ", ""));

    verify(whatsappAlertService, never()).sendWhatsappSms(anyString(), anyString(), anyList());
  }

  @Test
  void whatsappReport_shouldSendOneMessagePerHubToEveryConfiguredNumber() {
    service.whatsappReport(
        new LiveStockAlertReport(
            DETECTED_AT,
            List.of(
                row(1, "Gurgaon Sector 52", 5, "Sokora Facial"),
                row(1, "Gurgaon Sector 52", 6, "Rica Wax"),
                row(2, "Delhi Saket", 6, "Rica Wax"))),
        "inventory_oos",
        List.of("9999999999", "8888888888"));

    // 2 hubs x 2 numbers = 4 sends, never one combined message covering both hubs.
    ArgumentCaptor<List<String>> params = ArgumentCaptor.forClass(List.class);
    verify(whatsappAlertService, times(4))
        .sendWhatsappSms(anyString(), anyString(), params.capture());

    List<List<String>> allParams = params.getAllValues();
    assertThat(allParams).hasSize(4);
    assertThat(allParams)
        .extracting(p -> p.get(0))
        .containsExactlyInAnyOrder(
            "Gurgaon Sector 52 Product Hub: Sokora Facial, Rica Wax",
            "Gurgaon Sector 52 Product Hub: Sokora Facial, Rica Wax",
            "Delhi Saket Product Hub: Rica Wax",
            "Delhi Saket Product Hub: Rica Wax");
    // no message should ever mention the other hub — one hub per message, not a combined block.
    assertThat(allParams)
        .allSatisfy(
            p -> {
              assertThat(p.get(1)).isEqualTo("Uniware");
              assertThat(p.get(2)).contains("16th June, 2026");
            });

    verify(whatsappAlertService, times(2))
        .sendWhatsappSms(org.mockito.ArgumentMatchers.eq("9999999999"), anyString(), anyList());
    verify(whatsappAlertService, times(2))
        .sendWhatsappSms(org.mockito.ArgumentMatchers.eq("8888888888"), anyString(), anyList());
  }

  // ================================================================
  // helpers
  // ================================================================

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    int index = 0;
    while ((index = haystack.indexOf(needle, index)) != -1) {
      count++;
      index += needle.length();
    }
    return count;
  }

  private static LiveStockAlertRow row(int hubId, String hubName, int productId, String productName) {
    return new LiveStockAlertRow(hubId, hubName, productId, productName, 0f);
  }

  private static LiveStockAlertProjection projection(
      int hubId, String hubName, int productId, String productName) {
    return new LiveStockAlertProjection() {
      @Override
      public Integer getHubId() {
        return hubId;
      }

      @Override
      public String getHubName() {
        return hubName;
      }

      @Override
      public Integer getProductId() {
        return productId;
      }

      @Override
      public String getProductName() {
        return productName;
      }

      @Override
      public Float getLiveStock() {
        return 0f;
      }
    };
  }
}
