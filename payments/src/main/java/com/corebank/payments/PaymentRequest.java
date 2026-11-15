package com.corebank.payments;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PaymentRequest(
    @NotNull Long fromAccount,
    @NotNull Long toAccount,
    @Positive long amount,
    @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
    @Size(max = 140) String reference) {}
