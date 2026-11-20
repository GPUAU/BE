package com.gpumarket.domain.agent.service;

import com.gpumarket.domain.agent.dto.PublicContextDto;
import com.gpumarket.global.api.InvalidRequestException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AgentSessionStore {
  private static final Duration TTL = Duration.ofMinutes(30);
  private static final int MAX_SESSIONS = 1_024;
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");

  private final Clock clock;
  private final Map<String, Session> sessions = new LinkedHashMap<>();

  public AgentSessionStore() {
    this(Clock.systemUTC());
  }

  AgentSessionStore(Clock clock) {
    this.clock = clock;
  }

  synchronized String create(Conditions conditions) {
    Instant now = clock.instant();
    sessions.values().removeIf(session -> !session.expiresAt.isAfter(now));
    if (sessions.size() >= MAX_SESSIONS) {
      sessions.remove(sessions.keySet().iterator().next());
    }

    String id = UUID.randomUUID().toString();
    sessions.put(id, new Session(conditions, now.plus(TTL)));
    return id;
  }

  synchronized Session require(String id) {
    validateId(id);
    Session session = sessions.get(id);
    if (session == null || !session.expiresAt.isAfter(clock.instant())) {
      sessions.remove(id);
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return session;
  }

  synchronized void complete(String id, Conditions conditions) {
    // A deletion may take place while the public price query is running.
    require(id).conditions = conditions;
  }

  synchronized void delete(String id) {
    validateId(id);
    sessions.remove(id);
  }

  private static void validateId(String id) {
    if (!ID.matcher(id).matches()) {
      throw new InvalidRequestException("대화 세션 ID 형식을 확인해 주세요.");
    }
  }

  static final class Session {
    Conditions conditions;
    private final Instant expiresAt;

    private Session(Conditions conditions, Instant expiresAt) {
      this.conditions = conditions;
      this.expiresAt = expiresAt;
    }
  }

  record Conditions(PublicContextDto publicContext, String providerId, boolean assumedContract) {}
}
