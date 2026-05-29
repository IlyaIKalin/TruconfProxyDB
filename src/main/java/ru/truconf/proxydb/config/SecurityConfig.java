package ru.truconf.proxydb.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.truconf.proxydb.api.OutboxDtos.ErrorBody;
import ru.truconf.proxydb.api.OutboxDtos.ErrorDetail;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfig {

  private static final String API_KEY_HEADER = "X-API-Key";

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
      ObjectMapper objectMapper) throws Exception {
    http
        .csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(session ->
            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
            (request, response, authException) ->
                writeUnauthorized(response, objectMapper)))
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers(HttpMethod.GET, "/", "/index.html", "/favicon.ico")
            .permitAll()
            .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
            .permitAll()
            .requestMatchers("/api/v1/**")
            .authenticated()
            .anyRequest()
            .denyAll())
        .addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }

  @Bean
  UserDetailsService userDetailsService() {
    return username -> {
      throw new UsernameNotFoundException(username);
    };
  }

  @Bean
  ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(
      AppProperties properties,
      ObjectMapper objectMapper) {
    return new ApiKeyAuthenticationFilter(properties.proxyApiKey(), objectMapper);
  }

  static final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final byte[] expectedApiKey;
    private final ObjectMapper objectMapper;

    ApiKeyAuthenticationFilter(String expectedApiKey, ObjectMapper objectMapper) {
      this.expectedApiKey = expectedApiKey.getBytes(StandardCharsets.UTF_8);
      this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
      return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {
      String actualApiKey = request.getHeader(API_KEY_HEADER);
      if (!matches(actualApiKey)) {
        SecurityContextHolder.clearContext();
        writeUnauthorized(response, objectMapper);
        return;
      }

      UsernamePasswordAuthenticationToken authentication =
          new UsernamePasswordAuthenticationToken(
              "api-key",
              null,
              List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT")));
      SecurityContextHolder.getContext().setAuthentication(authentication);

      try {
        filterChain.doFilter(request, response);
      } finally {
        SecurityContextHolder.clearContext();
      }
    }

    private boolean matches(String actualApiKey) {
      if (actualApiKey == null) {
        return false;
      }
      return MessageDigest.isEqual(
          expectedApiKey,
          actualApiKey.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static void writeUnauthorized(
      HttpServletResponse response,
      ObjectMapper objectMapper) throws IOException {
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(
        response.getOutputStream(),
        new ErrorBody(new ErrorDetail(
            "UNAUTHORIZED",
            "Authentication required",
            List.of())));
  }
}
