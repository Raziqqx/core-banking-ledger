package com.corebank.app;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/** springdoc metadata: served at /v3/api-docs and /swagger-ui.html (both public). */
@Configuration
@OpenAPIDefinition(
    info = @Info(
        title = "Core Banking Ledger API",
        version = "v1",
        description = "Double-entry ledger, accounts and payments. Errors are application/problem+json."),
    security = @SecurityRequirement(name = "bearer"))
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {}
