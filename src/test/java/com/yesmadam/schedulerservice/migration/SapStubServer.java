package com.yesmadam.schedulerservice.migration;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
          byte[] out = responseFor(path).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(statusFor(path), out.length);
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
