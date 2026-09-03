package com.yesmadam.schedulerservice.jobs.producthublivestock.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ProductHubWatchlistTest {

  @Test
  void shouldExposeAHardcodedNonEmptyProductListForEveryConfiguredHub() {
    ProductHubWatchlist watchlist = new ProductHubWatchlist();

    Map<Integer, List<Integer>> hubProductIds = watchlist.hubProductIds();

    assertThat(hubProductIds).isNotEmpty();
    assertThat(hubProductIds.values()).allSatisfy(ids -> assertThat(ids).isNotEmpty());
  }

  @Test
  void shouldNotCrossJoinOneHubsProductsIntoAnother() {
    ProductHubWatchlist watchlist = new ProductHubWatchlist();

    Map<Integer, List<Integer>> hubProductIds = watchlist.hubProductIds();

    // hub 70's watchlist is disjoint from every other hub's in the source data — a sanity check
    // that hubs were kept separate rather than merged into one shared product list.
    assertThat(hubProductIds.get(70)).contains(14, 17, 33);
    assertThat(hubProductIds.get(71)).doesNotContain(14, 17, 33);
  }
}
