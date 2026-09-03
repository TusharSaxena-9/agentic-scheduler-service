package com.yesmadam.schedulerservice.jobs.producthublivestock.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.yesmadam.schedulerservice.jobs.producthublivestock.projections.LiveStockAlertProjection;

class LiveStockAlertDaoTest {

  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final LiveStockAlertDao dao = new LiveStockAlertDao(jdbcTemplate);

  @Test
  void shouldSkipTheQueryEntirelyWhenTheWatchlistIsEmpty() {
    List<LiveStockAlertProjection> found = dao.findOutOfStock(Map.of());

    assertThat(found).isEmpty();
    verify(jdbcTemplate, never()).query(anyString(), any(Object[].class), any(RowMapper.class));
  }

  @Test
  void shouldBuildOneTupleInClauseAndBindEveryPairPositionally() {
    Map<Integer, List<Integer>> hubProductIds = new LinkedHashMap<>();
    hubProductIds.put(70, List.of(14, 17));
    hubProductIds.put(71, List.of(2));

    dao.findOutOfStock(hubProductIds);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate).query(sql.capture(), params.capture(), any(RowMapper.class));

    // one query, one round trip — a (?,?) tuple per hub/product pair, never per hub.
    assertThat(sql.getValue()).contains("(pil.product_hub_id, pil.product_id) IN ((?,?), (?,?), (?,?))");
    assertThat(params.getValue()).containsExactly(70, 14, 70, 17, 71, 2);
  }
}
