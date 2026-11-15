package com.corebank.ledger;

/** One leg of a journal entry: a signed change to an account's balance, in minor units. */
public record Posting(long accountId, long amount, String currency) {}
