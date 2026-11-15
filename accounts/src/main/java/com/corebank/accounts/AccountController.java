package com.corebank.accounts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {
  public record OpenAccountRequest(
      @NotBlank @Size(max = 100) String owner,
      @Pattern(regexp = "[A-Z]{3}") String currency,
      @PositiveOrZero long overdraftLimit) {}

  private final AccountRepository accounts;
  private final AccountAccess access;

  public AccountController(AccountRepository accounts, AccountAccess access) {
    this.accounts = accounts;
    this.access = access;
  }

  /** Admin only (enforced in the security config); {@code owner} is the customer's JWT subject. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Account open(@Valid @RequestBody OpenAccountRequest req) {
    return accounts.insert(req.owner(), req.currency(), req.overdraftLimit());
  }

  @GetMapping("/{id}")
  public Account get(@PathVariable long id) {
    access.requireOwner(id);
    return accounts.get(id);
  }
}
