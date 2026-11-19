package com.gpumarket.domain.collection.repository;

import com.gpumarket.domain.collection.dto.CollectionAlertsResponseDto.Alert;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CollectionAlertRepository {
  private final JdbcTemplate jdbc;

  public CollectionAlertRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static String id(ResultSet rs, String name) throws SQLException {
    long value = rs.getLong(name);
    return rs.wasNull() ? null : Long.toString(value);
  }

  private static OffsetDateTime utc(Instant time) {
    return time.atOffset(ZoneOffset.UTC);
  }

  public List<Alert> find(
      Instant asOf,
      Instant from,
      Instant to,
      String alertType,
      String handlingStatus,
      Instant afterTime,
      Long afterId,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT"
                + " alert_id,target_id,run_id,check_id,source_id,alert_type,previous_observation_id,current_observation_id,detected_at,rule_version,reason,handling_status"
                + " FROM collection_alert WHERE detected_at<=?");
    List<Object> args = new ArrayList<>();
    args.add(utc(asOf));

    if (from != null) {
      sql.append(" AND detected_at>=?");
      args.add(utc(from));
    }

    if (to != null) {
      sql.append(" AND detected_at<?");
      args.add(utc(to));
    }

    if (alertType != null) {
      sql.append(" AND alert_type=?");
      args.add(alertType.toLowerCase(Locale.ROOT));
    }

    if (handlingStatus != null) {
      sql.append(" AND handling_status=?");
      args.add(handlingStatus.toLowerCase(Locale.ROOT));
    }

    if (afterTime != null) {
      sql.append(" AND (detected_at>? OR (detected_at=? AND alert_id>?))");
      args.add(utc(afterTime));
      args.add(utc(afterTime));
      args.add(afterId);
    }

    sql.append(" ORDER BY detected_at,alert_id LIMIT ?");
    args.add(limit);
    return jdbc.query(
        sql.toString(),
        (rs, row) ->
            new Alert(
                id(rs, "alert_id"),
                id(rs, "target_id"),
                id(rs, "run_id"),
                id(rs, "check_id"),
                id(rs, "source_id"),
                rs.getString("alert_type").toUpperCase(Locale.ROOT),
                id(rs, "previous_observation_id"),
                id(rs, "current_observation_id"),
                rs.getObject("detected_at", OffsetDateTime.class).toInstant(),
                rs.getString("rule_version"),
                rs.getString("reason"),
                rs.getString("handling_status").toUpperCase(Locale.ROOT)),
        args.toArray());
  }
}
