package com.yesmadam.schedulerservice.jobs.billingcheck.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTataResponse;

@ExtendWith(MockitoExtension.class)
class BillingCheckWhatsappServiceTest {

  @Mock RestTemplate restTemplate;

  private BillingCheckWhatsappService service;

  @BeforeEach
  void setUp() {
    service = new BillingCheckWhatsappService(restTemplate);
    ReflectionTestUtils.setField(service, "authKeyToken", "test-token");
    ReflectionTestUtils.setField(service, "whatsappUrl", "https://tata.example/send");
  }

  @Test
  @SuppressWarnings("unchecked")
  void send_shouldBuildBodyAndTwoQuickReplyButtonsCarryingTheOrderId() throws Exception {
    service.send("9999900011", "feedback_addon_1", List.of("Priya", "Beauty", "1499", "Facial"), 42L);

    ArgumentCaptor<HttpEntity<Object>> entity = ArgumentCaptor.forClass(HttpEntity.class);
    verify(restTemplate)
        .postForEntity(eq("https://tata.example/send"), entity.capture(), eq(WhatsappTataResponse.class));
    JsonNode json = new ObjectMapper().valueToTree(entity.getValue().getBody());

    assertThat(json.get("to").asText()).isEqualTo("+919999900011");
    assertThat(json.at("/template/name").asText()).isEqualTo("feedback_addon_1");
    JsonNode components = json.at("/template/components");
    assertThat(components).hasSize(3);

    JsonNode body = components.get(0);
    assertThat(body.get("type").asText()).isEqualTo("body");
    assertThat(body.get("parameters")).hasSize(4);
    assertThat(body.at("/parameters/2/text").asText()).isEqualTo("1499");

    JsonNode yes = components.get(1);
    assertThat(yes.get("type").asText()).isEqualTo("button");
    assertThat(yes.get("sub_type").asText()).isEqualTo("quick_reply");
    assertThat(yes.get("index").asText()).isEqualTo("0");
    assertThat(yes.at("/parameters/0/type").asText()).isEqualTo("payload");
    assertThat(yes.at("/parameters/0/payload").asText()).isEqualTo("BILLING_CHECK_YES:42");
    assertThat(yes.at("/parameters/0").has("text")).isFalse();

    assertThat(components.get(2).at("/parameters/0/payload").asText()).isEqualTo("BILLING_CHECK_NO:42");
    assertThat(components.get(2).get("index").asText()).isEqualTo("1");
  }

  @Test
  void send_shouldWrapAnyProviderFailureSoTheCallerCanMoveOn() {
    when(restTemplate.postForEntity(any(String.class), any(), eq(WhatsappTataResponse.class)))
        .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

    assertThatThrownBy(() -> service.send("9999900011", "t", List.of("a"), 1L))
        .isInstanceOf(BillingCheckSendException.class);
  }
}
