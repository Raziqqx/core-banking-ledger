package com.corebank.notifications;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationController {
  private final JdbcClient jdbc;

  public NotificationController(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping("/notifications")
  public List<Map<String, Object>> forAccount(@RequestParam long accountId) {
    return jdbc.sql(
            "SELECT event_id, payment_id, message, created_at FROM notifications"
                + " WHERE account_id = ? ORDER BY id DESC LIMIT 100")
        .param(accountId)
        .query()
        .listOfRows();
  }
}
