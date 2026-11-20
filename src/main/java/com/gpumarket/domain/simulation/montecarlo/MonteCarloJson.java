package com.gpumarket.domain.simulation.montecarlo;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class MonteCarloJson {
  private static final ObjectWriter SORTED_INPUT_WRITER =
      JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build().writer();

  private MonteCarloJson() {}

  public static String configHash(StrictFields validatedRequest) {
    try {
      return sha256(SORTED_INPUT_WRITER.writeValueAsBytes(validatedRequest));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cannot serialize Monte Carlo inputs", exception);
    }
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  public interface StrictFields {
    @JsonAnySetter
    default void rejectUnknownField(String name, JsonNode value) {
      throw new IllegalArgumentException("지원하지 않는 요청 필드입니다.");
    }
  }

  public static class ExactNumber extends JsonDeserializer<Double> {
    @Override
    public Double deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      if (!parser.currentToken().isNumeric()) {
        return (Double) context.handleUnexpectedToken(Double.class, parser);
      }
      double value = parser.getDoubleValue();
      return value == 0.0 ? 0.0 : value;
    }
  }

  static <E extends Enum<E>> E enumName(JsonNode value, Class<E> type) {
    if (!value.isTextual()) {
      throw new IllegalArgumentException("시뮬레이션 선택값을 이름으로 입력하세요.");
    }
    return Enum.valueOf(type, value.textValue());
  }
}
