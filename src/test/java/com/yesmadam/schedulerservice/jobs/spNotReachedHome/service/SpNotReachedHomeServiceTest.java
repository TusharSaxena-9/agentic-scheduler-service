package com.yesmadam.schedulerservice.jobs.spNotReachedHome.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.yesmadam.schedulerservice.jobs.spNotReachedHome.model.ReachedHomeUpdate;
import com.yesmadam.schedulerservice.jobs.spNotReachedHome.projections.PendingNotReachedHomeProjection;
import com.yesmadam.schedulerservice.jobs.spNotReachedHome.projections.SpLatestLocationProjection;
import com.yesmadam.schedulerservice.jobs.spNotReachedHome.repository.SpNotReachedHomeBatchDao;
import com.yesmadam.schedulerservice.jobs.spNotReachedHome.repository.SpNotReachedHomeRepository;

/**
 * Covers the parts of SAAB-90 that moved from SQL into Java: the haversine re-check and the
 * threshold comparison that decides whether an SP is marked home. No database — the repository is
 * mocked, because what is being tested is the decision, not the query.
 *
 * <p>Coordinates are real ones from the ticket's own staging run (SP 12533, home {@code 18.6010921,
 * 73.7641245}).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpNotReachedHomeServiceTest {

  private static final String HOME_LAT = "18.6010921";
  private static final String HOME_LON = "73.7641245";
  private static final int THRESHOLD_METRES = 300;

  @Mock SpNotReachedHomeRepository repository;
  @Mock SpNotReachedHomeBatchDao batchDao;
  @InjectMocks SpNotReachedHomeService service;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(service, "distanceThreshold", THRESHOLD_METRES);
  }

  // Plain stubs rather than Mockito mocks: these are data carriers, and building a mock inside the
  // argument list of an outer when(...) is nested stubbing, which Mockito rejects.
  private static PendingNotReachedHomeProjection pending(
      long id, int spId, String lat, String lon) {
    return new PendingNotReachedHomeProjection() {
      @Override
      public Long getId() {
        return id;
      }

      @Override
      public Integer getSpId() {
        return spId;
      }

      @Override
      public String getHomeLat() {
        return lat;
      }

      @Override
      public String getHomeLon() {
        return lon;
      }
    };
  }

  private static SpLatestLocationProjection location(int spId, double lat, double lon) {
    return new SpLatestLocationProjection() {
      @Override
      public Integer getSpId() {
        return spId;
      }

      @Override
      public Double getLat() {
        return lat;
      }

      @Override
      public Double getLon() {
        return lon;
      }
    };
  }

  @Test
  @DisplayName("an SP standing at their home coordinates is marked reached-home")
  void marksSpAtHome() {
    when(repository.findPendingForToday())
        .thenReturn(List.of(pending(1L, 12533, HOME_LAT, HOME_LON)));
    when(repository.findLatestLocations(anyList()))
        .thenReturn(List.of(location(12533, 18.6010921, 73.7641245)));
    when(batchDao.markReachedHome(anyList())).thenReturn(1);

    assertThat(service.autoMarkReachedHome()).isEqualTo(1);

    ArgumentCaptor<List<ReachedHomeUpdate>> captor = ArgumentCaptor.forClass(List.class);
    verify(batchDao).markReachedHome(captor.capture());
    assertThat(captor.getValue())
        .singleElement()
        .satisfies(u -> assertThat(u.getTrackerRowId()).isEqualTo(1L));
  }

  @Test
  @DisplayName("an SP ~2.2km away is left alone")
  void leavesDistantSpAlone() {
    when(repository.findPendingForToday())
        .thenReturn(List.of(pending(1L, 12533, HOME_LAT, HOME_LON)));
    // ~0.02 degrees of latitude ≈ 2.2 km — comfortably outside 300 m
    when(repository.findLatestLocations(anyList()))
        .thenReturn(List.of(location(12533, 18.6210921, 73.7641245)));

    assertThat(service.autoMarkReachedHome()).isZero();
    verify(batchDao, never()).markReachedHome(anyList());
  }

  @Test
  @DisplayName("an SP with no ping in the 10-minute window is skipped, not marked")
  void skipsSpWithNoRecentLocation() {
    when(repository.findPendingForToday())
        .thenReturn(List.of(pending(1L, 12533, HOME_LAT, HOME_LON)));
    when(repository.findLatestLocations(anyList())).thenReturn(Collections.emptyList());

    assertThat(service.autoMarkReachedHome()).isZero();
    verify(batchDao, never()).markReachedHome(anyList());
  }

  @Test
  @DisplayName("a non-numeric home coordinate skips that SP without failing the cycle")
  void badCoordinateSkipsOnlyThatSp() {
    when(repository.findPendingForToday())
        .thenReturn(
            List.of(
                pending(1L, 111, "not-a-number", HOME_LON), pending(2L, 222, HOME_LAT, HOME_LON)));
    when(repository.findLatestLocations(anyList()))
        .thenReturn(
            List.of(location(111, 18.6010921, 73.7641245), location(222, 18.6010921, 73.7641245)));
    when(batchDao.markReachedHome(anyList())).thenReturn(1);

    assertThat(service.autoMarkReachedHome()).isEqualTo(1);

    ArgumentCaptor<List<ReachedHomeUpdate>> captor = ArgumentCaptor.forClass(List.class);
    verify(batchDao).markReachedHome(captor.capture());
    // only the SP with a parseable home address survives
    assertThat(captor.getValue())
        .singleElement()
        .satisfies(u -> assertThat(u.getTrackerRowId()).isEqualTo(2L));
  }

  @Test
  @DisplayName("no pending rows short-circuits before the location query runs")
  void noPendingRowsShortCircuits() {
    when(repository.findPendingForToday()).thenReturn(Collections.emptyList());

    assertThat(service.autoMarkReachedHome()).isZero();
    verify(repository, never()).findLatestLocations(anyList());
    verify(batchDao, never()).markReachedHome(anyList());
  }

  @Test
  @DisplayName("the configured threshold is the one passed to the eligibility query")
  void passesConfiguredThresholdToQuery() {
    when(repository.findCandidatesFurtherThan(anyInt())).thenReturn(Collections.emptyList());

    service.insertSpNotReachedHome();

    verify(repository).findCandidatesFurtherThan(THRESHOLD_METRES);
    verify(batchDao, never()).upsertCandidates(anyList());
  }

  @Test
  @DisplayName("no candidates means no write at all")
  void noCandidatesMeansNoWrite() {
    when(repository.findCandidatesFurtherThan(anyInt())).thenReturn(Collections.emptyList());

    assertThat(service.insertSpNotReachedHome()).isZero();
    verify(batchDao, never()).upsertCandidates(any());
  }

  @Test
  @DisplayName("the delete step reports how many rows it removed")
  void deleteReportsRowCount() {
    when(repository.deleteSpWithActiveJob()).thenReturn(4);

    assertThat(service.removeSpWithActiveJob()).isEqualTo(4);
  }
}
