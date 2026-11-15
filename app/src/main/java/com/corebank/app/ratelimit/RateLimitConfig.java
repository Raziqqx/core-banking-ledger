package com.corebank.app.ratelimit;

import com.corebank.common.CurrentCaller;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Applies {@link PaymentRateLimiter} to {@code POST /payments}. It runs as an MVC interceptor, i.e.
 * after the security filters, so the client key is the authenticated JWT subject (unauthenticated
 * requests are rejected before they can drain anyone's bucket). Idempotent retries count too.
 */
@Configuration
public class RateLimitConfig implements WebMvcConfigurer {
  private final PaymentRateLimiter limiter;
  private final CurrentCaller caller;

  public RateLimitConfig(PaymentRateLimiter limiter, CurrentCaller caller) {
    this.limiter = limiter;
    this.caller = caller;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new HandlerInterceptor() {
      @Override
      public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if ("POST".equals(req.getMethod())) {
          long wait = limiter.tryConsume(caller.subject());
          if (wait > 0) {
            throw new RateLimitExceededException(wait);
          }
        }
        return true;
      }
    }).addPathPatterns("/payments");
  }
}
