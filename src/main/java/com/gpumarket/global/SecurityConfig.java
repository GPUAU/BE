package com.gpumarket.global;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@Profile("prod | production | (local & deploy) | (!local & !deploy)")
public class SecurityConfig {
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    // Fail closed until the team selects and implements an access mechanism.
    return http.authorizeHttpRequests(auth -> auth.anyRequest().denyAll()).build();
  }
}
