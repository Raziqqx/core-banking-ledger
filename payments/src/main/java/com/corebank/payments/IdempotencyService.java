package com.corebank.payments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Idempotency keys for POST /payments. The first request with a key claims it with an
 * INSERT ... ON CONFLICT DO NOTHING, which the primary key makes atomic across threads and
 * instances. Later requests with that key get the stored response if the body matches, or a
 * conflict if the body differs or the first request has not finished yet.
 */
@Service
public class IdempotencyService {
  public sealed interface Outcome {}

  /** This caller owns the key and must execute the request, then call {@link #complete}. */
  public record Claimed() implements Outcome {}

  public record Replay(int status, String body) implements Outcome {}

  public record Conflict(String reason) implements Outcome {}

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public IdempotencyService(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Outcome begin(String key, Object request) {
    String hash = hash(request);
    int inserted =
        jdbc.sql(
                "INSERT INTO idempotency_keys(key, request_hash, status) VALUES (?, ?, 'IN_PROGRESS')"
                    + " ON CONFLICT (key) DO NOTHING")
            .params(key, hash)
            .update();
    if (inserted == 1) {
      return new Claimed();
    }
    return jdbc.sql(
            "SELECT request_hash, status, response_status, response_body FROM idempotency_keys"
                + " WHERE key = ?")
        .param(key)
        .query(
            (rs, i) -> {
              if (!rs.getString(1).equals(hash)) {
                return (Outcome) new Conflict("idempotency key reused with a different request");
              }
              if (rs.getString(2).equals("IN_PROGRESS")) {
                return new Conflict("a request with this idempotency key is still in progress");
              }
              return new Replay(rs.getInt(3), rs.getString(4));
            })
        .single();
  }

  /**
   * Links a claimed key to the payment it is executing, before the saga starts, so recovery can
   * complete the key if the process dies mid-saga.
   */
  public void attach(String key, UUID paymentId) {
    jdbc.sql("UPDATE idempotency_keys SET payment_id = ? WHERE key = ?").params(paymentId, key).update();
  }

  public void complete(String key, UUID paymentId, int status, String body) {
    jdbc.sql(
            "UPDATE idempotency_keys SET status = 'COMPLETED', payment_id = ?, response_status = ?,"
                + " response_body = ? WHERE key = ?")
        .params(paymentId, status, body, key)
        .update();
  }

  /** SHA-256 of the request's JSON form. Records serialise fields in declaration order. */
  String hash(Object request) {
    try {
      byte[] bytes = json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
