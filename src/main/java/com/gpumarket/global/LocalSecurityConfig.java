package com.gpumarket.global;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpumarket.global.api.ApiErrorResponder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@Profile("local & !deploy & !prod & !production")
public class LocalSecurityConfig {
  @Bean
  SecurityFilterChain localSecurityFilterChain(HttpSecurity http, ObjectMapper mapper)
      throws Exception {
    var errors = new ApiErrorResponder(mapper);
    return http.csrf(csrf -> csrf.disable())
        // 막힌 요청도 빈 403 대신 {isSuccess:false, code, message}로 (ApiErrorResponder)
        .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
        .authorizeHttpRequests(
            auth -> {
              auth.requestMatchers(
                      HttpMethod.GET,
                      "/swagger-ui.html",
                      "/swagger-ui/**",
                      "/v3/api-docs",
                      "/v3/api-docs/**")
                  .permitAll();
              PublicApiAccess.permitPublicApis(auth);
              auth.anyRequest().denyAll();
            })
        .build();
  }
}
