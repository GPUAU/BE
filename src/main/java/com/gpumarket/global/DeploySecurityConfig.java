package com.gpumarket.global;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * deploy 프로필 접근 정책.
 *
 * <p>BE는 클러스터 내부 Service로만 열려 있고 FE 프록시를 거쳐 호출된다. 팀 도메인은 캠퍼스 IP만 허용한다. 화면에서 쓰는 공개 API만 허용하고
 * Swagger와 그 밖의 경로·메서드는 거부한다. 세션·쿠키를 쓰지 않는 API라 CSRF는 끈다. 사용자 인증이 도입되면 이 정책을 교체한다.
 */
@Configuration
@Profile("deploy & !local & !prod & !production")
public class DeploySecurityConfig {
  @Bean
  SecurityFilterChain deploySecurityFilterChain(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth -> {
              // 관리 포트(8081)의 health·prometheus만 허용 (클러스터 내부 프로브·Prometheus 수집용).
              auth.requestMatchers(
                      HttpMethod.GET,
                      "/actuator/health",
                      "/actuator/health/**",
                      "/actuator/prometheus")
                  .permitAll();
              PublicApiAccess.permitPublicApis(auth);
              auth.anyRequest().denyAll();
            })
        .build();
  }
}
