package com.corebank.ledger;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Independent check of the ledger invariants, recomputed from the postings: every entry balances
 * per currency, every cached balance equals the sum of its postings, and every cached held amount
 * equals the sum of its active holds.
 */
@Component
public class LedgerAuditor {
  public record Report(
      List<Long> unbalancedEntries, List<Long> balanceMismatches, List<Long> holdMismatches) {
    @JsonProperty
    public boolean ok() {
      return unbalancedEntries.isEmpty() && balanceMismatches.isEmpty() && holdMismatches.isEmpty();
    }
  }

  private final JdbcClient jdbc;

  public LedgerAuditor(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Report audit() {
    List<Long> unbalanced =
        ids("SELECT DISTINCT entry_id FROM postings GROUP BY entry_id, currency HAVING SUM(amount) <> 0");
    List<Long> balances =
        ids(
            "SELECT a.id FROM accounts a LEFT JOIN postings p ON p.account_id = a.id"
                + " GROUP BY a.id, a.balance HAVING a.balance <> COALESCE(SUM(p.amount), 0)");
    List<Long> holds =
        ids(
            "SELECT a.id FROM accounts a LEFT JOIN holds h ON h.account_id = a.id AND h.status = 'ACTIVE'"
                + " GROUP BY a.id, a.held HAVING a.held <> COALESCE(SUM(h.amount), 0)");
    return new Report(unbalanced, balances, holds);
  }

  private List<Long> ids(String sql) {
    return jdbc.sql(sql).query(Long.class).list();
  }
}
