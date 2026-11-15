package com.corebank.app.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Local stand-in for an OAuth2 authorization server, active only under the {@code dev} profile.
 * The RSA key pair is generated in memory at startup, so no key material is ever committed and
 * tokens die with the process. Production points the resource server at a real issuer instead.
 */
@Configuration
@Profile("dev")
public class DevTokenIssuer {
  public static final String ISSUER = "urn:corebank:dev-issuer";

  private final RSAKey key;
  private final JwtEncoder encoder;

  public DevTokenIssuer() throws JOSEException {
    this.key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
    this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
  }

  /** Verifies signature (RS256, our public key), expiry/not-before and the issuer claim. */
  @Bean
  JwtDecoder jwtDecoder() throws JOSEException {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
    return decoder;
  }

  public String issue(String subject, List<String> roles, Duration ttl) {
    return issue(subject, roles, Instant.now(), ttl);
  }

  /** Explicit issue time, so tests can mint already-expired tokens. */
  public String issue(String subject, List<String> roles, Instant now, Duration ttl) {
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(subject)
            .issuedAt(now)
            .expiresAt(now.plus(ttl))
            .claim("roles", roles)
            .build();
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
