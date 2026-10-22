package com.yesmadam.schedulerservice.migration;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;

/**
 * A throwaway HTTP server standing in for the Cinntra / SAP Service Layer.
 *
 * <p>Lets tests capture the exact JSON a job posts, which is the only way to assert payload parity
 * for the jobs that call SAP through a raw {@code HttpURLConnection} rather than a mockable {@code
 * RestTemplate}. Needs no dependencies beyond the JDK.
 */
final class SapStubServer implements AutoCloseable {

  /** One captured call: the path it hit and the body it sent. */
  record Call(String path, String body) {}

  private final HttpServer server;
  private final List<Call> calls = new ArrayList<>();
  private final Map<String, String> responsesByPathSuffix = new LinkedHashMap<>();
  private final Map<String, Integer> statusByPathSuffix = new LinkedHashMap<>();

  /** Session id to how many more requests it is still accepted for. */
  private final Map<String, Integer> sessionAllowance = new HashMap<>();

  private String defaultResponse = "{}";

  SapStubServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String body;
          try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
          }
          String path = exchange.getRequestURI().getPath();
          synchronized (calls) {
            calls.add(new Call(path, body));
          }
          String cookie = exchange.getRequestHeaders().getFirst("Cookie");
          boolean rejected = cookie != null && isRejected(cookie);
          byte[] out =
              (rejected ? INVALID_SESSION : responseFor(path)).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(rejected ? 401 : statusFor(path), out.length);
          exchange.getResponseBody().write(out);
          exchange.close();
        });
    server.start();
  }

  private String responseFor(String path) {
    return responsesByPathSuffix.entrySet().stream()
        .filter(e -> path.endsWith(e.getKey()))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(defaultResponse);
  }

  private int statusFor(String path) {
    return statusByPathSuffix.entrySet().stream()
        .filter(e -> path.endsWith(e.getKey()))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(200);
  }

  /** B1 Service Layer's body for an expired or unknown session. */
  static final String INVALID_SESSION =
      "{\"error\":{\"code\":301,\"message\":{\"lang\":\"en-us\",\"value\":\"Invalid session.\"}}}";

  /** Every request carrying {@code B1SESSION=<sessionId>} gets B1's 401 "Invalid session." */
  SapStubServer rejectSession(String sessionId) {
    return rejectSessionAfter(sessionId, 0);
  }

  /**
   * {@code sessionId} is accepted for {@code accepted} more requests, then rejected like an expiry.
   */
  SapStubServer rejectSessionAfter(String sessionId, int accepted) {
    synchronized (sessionAllowance) {
      sessionAllowance.put(sessionId, accepted);
    }
    return this;
  }

  private boolean isRejected(String cookie) {
    synchronized (sessionAllowance) {
      for (Map.Entry<String, Integer> e : sessionAllowance.entrySet()) {
        if (cookie.contains("B1SESSION=" + e.getKey())) {
          if (e.getValue() > 0) {
            e.setValue(e.getValue() - 1);
            return false;
          }
          return true;
        }
      }
      return false;
    }
  }

  /**
   * Canned JSON returned with {@code status} for any request whose path ends with {@code
   * pathSuffix}.
   */
  SapStubServer respondTo(String pathSuffix, int status, String json) {
    statusByPathSuffix.put(pathSuffix, status);
    return respondTo(pathSuffix, json);
  }

  /** Canned JSON returned for any request whose path ends with {@code pathSuffix}. */
  SapStubServer respondTo(String pathSuffix, String json) {
    responsesByPathSuffix.put(pathSuffix, json);
    return this;
  }

  SapStubServer respondByDefault(String json) {
    this.defaultResponse = json;
    return this;
  }

  /** Base URL in the shape the jobs expect — trailing slash, paths appended directly. */
  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
  }

  List<Call> calls() {
    synchronized (calls) {
      return List.copyOf(calls);
    }
  }

  Call callTo(String pathSuffix) {
    return calls().stream()
        .filter(c -> c.path().endsWith(pathSuffix))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("no request was made to a path ending " + pathSuffix));
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
