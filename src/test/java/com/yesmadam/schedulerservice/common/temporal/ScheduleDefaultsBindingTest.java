package com.yesmadam.schedulerservice.common.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.convert.ConversionService;

import io.temporal.api.enums.v1.ScheduleOverlapPolicy;

/**
 * Guards the {@code temporal.schedule.defaults.*} property values against the conversion Spring
 * actually performs when injecting them into {@link TemporalScheduleRegistrar}.
 *
 * <p>Worth a test rather than a assumption: those fields are read during schedule registration,
 * which runs from {@code @PostConstruct}. A value Spring cannot convert fails bean creation, which
 * fails the application context, which crashloops the pod — so a typo in a duration string takes
 * the service down at boot rather than surfacing when a schedule next fires.
 */
class ScheduleDefaultsBindingTest {

  private final ConversionService conversionService =
      ApplicationConversionService.getSharedInstance();

  @Test
  @DisplayName("ISO-8601 duration defaults convert to Duration")
  void durationDefaultsConvert() {
    assertThat(conversionService.convert("PT5M", Duration.class)).isEqualTo(Duration.ofMinutes(5));
    assertThat(conversionService.convert("PT1M", Duration.class)).isEqualTo(Duration.ofMinutes(1));
    assertThat(conversionService.convert("PT1H", Duration.class)).isEqualTo(Duration.ofHours(1));
  }

  @Test
  @DisplayName("Overlap policy default converts to the protobuf enum constant")
  void overlapPolicyDefaultConverts() {
    assertThat(
            conversionService.convert("SCHEDULE_OVERLAP_POLICY_SKIP", ScheduleOverlapPolicy.class))
        .isEqualTo(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP);
  }

  @Test
  @DisplayName("Every overlap policy a job might override with is nameable in properties")
  void allOverlapPoliciesConvert() {
    for (ScheduleOverlapPolicy policy : ScheduleOverlapPolicy.values()) {
      if (policy == ScheduleOverlapPolicy.UNRECOGNIZED) {
        continue;
      }
      assertThat(conversionService.convert(policy.name(), ScheduleOverlapPolicy.class))
          .isEqualTo(policy);
    }
  }
}
