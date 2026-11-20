package com.gpumarket.domain.simulation.montecarlo;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

public final class MonteCarloJson {
  private MonteCarloJson() {}

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
      return parser.getDoubleValue();
    }
  }

  static <E extends Enum<E>> E enumName(JsonNode value, Class<E> type) {
    if (!value.isTextual()) {
      throw new IllegalArgumentException("시뮬레이션 선택값을 이름으로 입력하세요.");
    }
    return Enum.valueOf(type, value.textValue());
  }
}
