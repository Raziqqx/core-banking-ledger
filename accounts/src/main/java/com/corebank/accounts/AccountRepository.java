package com.corebank.accounts;

import com.corebank.common.DomainException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
  private static final String COLUMNS =
      "id, owner, currency, balance, held, overdraft_limit, system, version";
  private static final RowMapper<Account> MAPPER =
      (rs, i) ->
          new Account(
              rs.getLong("id"),
              rs.getString("owner"),
              rs.getString("currency"),
              rs.getLong("balance"),
              rs.getLong("held"),
              rs.getLong("overdraft_limit"),
              rs.getBoolean("system"),
              rs.getLong("version"));

  private final JdbcClient jdbc;

  public AccountRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Account insert(String owner, String currency, long overdraftLimit) {
    return jdbc.sql(
            "INSERT INTO accounts(owner, currency, overdraft_limit) VALUES (?, ?, ?) RETURNING "
                + COLUMNS)
        .params(owner, currency, overdraftLimit)
        .query(MAPPER)
        .single();
  }

  /** The bank's funding account for a currency, created on first use (race-safe upsert). */
  public long systemAccountId(String currency) {
    jdbc.sql(
            "INSERT INTO accounts(owner, currency, system) VALUES ('SYSTEM', ?, TRUE)"
                + " ON CONFLICT (currency) WHERE system DO NOTHING")
        .param(currency)
        .update();
    return jdbc.sql("SELECT id FROM accounts WHERE system AND currency = ?")
        .param(currency)
        .query(Long.class)
        .single();
  }

  public Account get(long id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id = ?")
        .param(id)
        .query(MAPPER)
        .optional()
        .orElseThrow(() -> DomainException.notFound("account " + id));
  }

  /**
   * Loads the accounts a transaction will modify. In PESSIMISTIC mode the rows are locked in
   * ascending id order, which gives every transaction the same lock order and rules out deadlocks.
   */
  public Map<Long, Account> load(Collection<Long> ids, LockingMode mode) {
    String sql =
        "SELECT " + COLUMNS + " FROM accounts WHERE id = ANY(?) ORDER BY id"
            + (mode == LockingMode.PESSIMISTIC ? " FOR UPDATE" : "");
    List<Account> rows = jdbc.sql(sql).param(ids.toArray(Long[]::new)).query(MAPPER).list();
    if (rows.size() != ids.size()) {
      throw DomainException.notFound("account in " + ids);
    }
    return rows.stream().collect(Collectors.toMap(Account::id, Function.identity()));
  }

  /**
   * Writes new balance/held values, guarded by the version read earlier. Under PESSIMISTIC locking
   * the guard always holds; under OPTIMISTIC a concurrent writer makes it fail and the caller retries.
   */
  public void save(Account a) {
    int n =
        jdbc.sql(
                "UPDATE accounts SET balance = ?, held = ?, version = version + 1"
                    + " WHERE id = ? AND version = ?")
            .params(a.balance(), a.held(), a.id(), a.version())
            .update();
    if (n != 1) {
      throw new OptimisticLockingFailureException("account " + a.id() + " changed concurrently");
    }
  }
}
