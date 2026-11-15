package com.corebank.app.genai;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feature flag: nothing here exists unless {@code genai.categoriser.enabled=true}, so with the flag
 * off the endpoint is a plain 404 and no client is created. {@code genai.categoriser.provider}
 * picks {@code mock} (default, deterministic, no network) or {@code anthropic} (reads
 * ANTHROPIC_API_KEY from the environment; never from config files).
 */
@Configuration
@ConditionalOnProperty(name = "genai.categoriser.enabled", havingValue = "true")
public class CategoriserConfig {

  @Bean
  TransactionCategoriser transactionCategoriser(
      @Value("${genai.categoriser.provider:mock}") String provider,
      @Value("${genai.categoriser.model:claude-opus-5-5}") String model) {
    return switch (provider) {
      case "mock" -> new MockCategoriser();
      case "anthropic" -> new AnthropicCategoriser(AnthropicOkHttpClient.fromEnv(), model);
      default -> throw new IllegalStateException("unknown genai.categoriser.provider: " + provider);
    };
  }
}
