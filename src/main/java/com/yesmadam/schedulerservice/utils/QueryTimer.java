package com.yesmadam.schedulerservice.utils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Times a batch of per-chunk DAO calls and records each one's row count, keyed by query name.
 * Shared across jobs — Rose Order's SP-eligibility queries return {@code Map<Integer, V>}, SEBAL
 * tagging's cohort queries return {@code List<V>}, auto-assign's reassign rollup returns
 * {@code Map<Long, Integer>}; all of them are just "how long did this take and how many rows came
 * back," so one class covers every shape via two key-agnostic overloads.
 *
 * <p>Create a fresh instance per chunk processed — never share one across chunks/threads. Both
 * {@code RoseOrderServiceImpl} and {@code SebalTaggingServiceImpl} are singleton Spring beans and
 * chunks may be processed concurrently by different activity executions, so a shared/static timer
 * would mix up results across chunks.
 */
public class QueryTimer {

  private final Map<String, Long> durationsMs = new LinkedHashMap<>();
  private final Map<String, Integer> rowCounts = new LinkedHashMap<>();

  /**
   * Runs {@code query}, records how long it took and how many rows it returned under {@code
   * queryName}, and returns the result.
   */
  public <K, V> Map<K, V> time(String queryName, Supplier<Map<K, V>> query) {
    long start = System.currentTimeMillis();
    Map<K, V> result = query.get();
    durationsMs.put(queryName, System.currentTimeMillis() - start);
    rowCounts.put(queryName, result.size());
    return result;
  }

  /**
   * Same as {@link #time(String, Supplier)}, for DAO calls that return a {@code List} instead of
   * an id-keyed {@code Map}.
   */
  public <V> List<V> time(String queryName, ListSupplier<V> query) {
    long start = System.currentTimeMillis();
    List<V> result = query.get();
    durationsMs.put(queryName, System.currentTimeMillis() - start);
    rowCounts.put(queryName, result.size());
    return result;
  }

  public Map<String, Long> getDurationsMs() {
    return durationsMs;
  }

  public Map<String, Integer> getRowCounts() {
    return rowCounts;
  }

  /**
   * Same shape as {@code Supplier<List<V>>} — a distinct named type so the two {@code time}
   * overloads above don't collide under generic type erasure (both would otherwise erase to
   * {@code time(String, Supplier)}).
   */
  public int time(String queryName, IntSupplier query) {
    long start = System.currentTimeMillis();
    int result = query.getAsInt();
    durationsMs.put(queryName, System.currentTimeMillis() - start);
    rowCounts.put(queryName, result);
    return result;
  }

  @FunctionalInterface
  public interface ListSupplier<V> extends Supplier<List<V>> {}
}
