package com.corebank.app.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /dev/token}: mints a 1-hour token for local use (dev profile only). */
@RestController
@Profile("dev")
public class DevTokenController {
  public record TokenRequest(
      @NotBlank @Size(max = 200) String subject,
      @NotEmpty List<@Pattern(regexp = "ADMIN|CUSTOMER") String> roles) {}

  public record TokenResponse(String access_token, String token_type, long expires_in) {}

  private static final Duration TTL = Duration.ofHours(1);
  private final DevTokenIssuer issuer;

  public DevTokenController(DevTokenIssuer issuer) {
    this.issuer = issuer;
  }

  @PostMapping("/dev/token")
  public TokenResponse token(@Valid @RequestBody TokenRequest req) {
    return new TokenResponse(issuer.issue(req.subject(), req.roles(), TTL), "Bearer", TTL.toSeconds());
  }
}
