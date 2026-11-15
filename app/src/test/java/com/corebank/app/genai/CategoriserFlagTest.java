package com.corebank.app.genai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CategoriserFlagTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(CategoriserConfig.class);

  @Test
  void offByDefault() {
    runner.run(ctx -> assertThat(ctx).doesNotHaveBean(TransactionCategoriser.class));
  }

  @Test
  void enabledDefaultsToTheMockProvider() {
    runner.withPropertyValues("genai.categoriser.enabled=true")
        .run(ctx -> assertThat(ctx.getBean(TransactionCategoriser.class))
            .isInstanceOf(MockCategoriser.class));
  }

  @Test
  void unknownProviderFailsStartup() {
    runner.withPropertyValues("genai.categoriser.enabled=true", "genai.categoriser.provider=nope")
        .run(ctx -> assertThat(ctx).hasFailed());
  }
}
