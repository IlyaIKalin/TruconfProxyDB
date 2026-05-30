package ru.truconf.proxydb.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class SecurityConfigTests {

  @Test
  void apiKeyFilterRunsForApiRequestInsideServletContextPath() {
    SecurityConfig.ApiKeyAuthenticationFilter filter =
        new SecurityConfig.ApiKeyAuthenticationFilter("test-api-key", new ObjectMapper());
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/tconf/api/v1/outbox/1");
    request.setContextPath("/tconf");
    request.setServletPath("/api/v1/outbox/1");

    assertThat(filter.shouldNotFilter(request)).isFalse();
  }

  @Test
  void apiKeyFilterSkipsHealthRequestInsideServletContextPath() {
    SecurityConfig.ApiKeyAuthenticationFilter filter =
        new SecurityConfig.ApiKeyAuthenticationFilter("test-api-key", new ObjectMapper());
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/tconf/actuator/health");
    request.setContextPath("/tconf");
    request.setServletPath("/actuator/health");

    assertThat(filter.shouldNotFilter(request)).isTrue();
  }
}
