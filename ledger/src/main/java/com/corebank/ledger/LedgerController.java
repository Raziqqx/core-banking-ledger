package com.corebank.ledger;

import com.corebank.accounts.Account;
import com.corebank.accounts.AccountRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class LedgerController {
  public record AmountRequest(@Positive long amount) {}

  public record TransferRequest(
      @NotNull Long fromAccount,
      @NotNull Long toAccount,
      @Positive long amount,
      @Size(max = 200) String description) {}

  public record EntryResponse(long entryId) {}

  public record Balance(long accountId, String currency, long balance, long held, long available) {}

  private final LedgerService ledger;
  private final LedgerAuditor auditor;
  private final AccountRepository accounts;

  public LedgerController(LedgerService ledger, LedgerAuditor auditor, AccountRepository accounts) {
    this.ledger = ledger;
    this.auditor = auditor;
    this.accounts = accounts;
  }

  @PostMapping("/accounts/{id}/deposits")
  @ResponseStatus(HttpStatus.CREATED)
  public EntryResponse deposit(@PathVariable long id, @Valid @RequestBody AmountRequest req) {
    return new EntryResponse(ledger.deposit(id, req.amount()));
  }

  /** Direct ledger transfer (no saga, no idempotency key); clients should prefer POST /payments. */
  @PostMapping("/transfers")
  @ResponseStatus(HttpStatus.CREATED)
  public EntryResponse transfer(@Valid @RequestBody TransferRequest req) {
    String desc = req.description() == null ? "transfer" : req.description();
    return new EntryResponse(
        ledger.transfer(req.fromAccount(), req.toAccount(), req.amount(), desc));
  }

  @GetMapping("/accounts/{id}/balance")
  public Balance balance(@PathVariable long id) {
    Account a = accounts.get(id);
    return new Balance(a.id(), a.currency(), a.balance(), a.held(), a.available());
  }

  @GetMapping("/accounts/{id}/history")
  public List<Map<String, Object>> history(
      @PathVariable long id, @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
    return ledger.history(id, limit);
  }

  @GetMapping("/ledger/audit")
  public LedgerAuditor.Report audit() {
    return auditor.audit();
  }
}
