package com.gpumarket.domain.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gpumarket.domain.market.dto.MarketChangesRequestDto;
import com.gpumarket.domain.market.dto.MarketChangesResponseDto.PriceChangeDto;
import com.gpumarket.domain.market.repository.MarketChangesRepository;
import com.gpumarket.domain.market.repository.MarketOfferRepository;
import com.gpumarket.domain.market.service.MarketChangesService;
import com.gpumarket.global.api.InvalidRequestException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class MarketChangesPostgresIntegrationTest {
  @Test
  void computesBoundaryChangesAndKeepsUnitsCorrectionsAndDisclosureSeparate() {
    var ds =
        new SingleConnectionDataSource(
            System.getenv("TEST_POSTGRES_URL"), System.getenv("TEST_POSTGRES_USER"), "", true);
    try {
      var jdbc = new JdbcTemplate(ds);
      createTables(jdbc);
      seed(jdbc);
      var named = new NamedParameterJdbcTemplate(jdbc);
      var service =
          new MarketChangesService(
              new MarketChangesRepository(named), new MarketOfferRepository(named));
      var now = OffsetDateTime.now(ZoneOffset.UTC);
      var start = now.minusHours(25);

      var first = service.list(request(start, now, "west", 2, null));
      assertThat(first.items())
          .extracting(PriceChangeDto::eventType)
          .containsExactly("PRICE_CHANGE", "UNIT_CHANGE");
      var change = first.items().get(0);
      assertThat(change.previousObservedAt()).isBefore(start.toInstant());
      assertThat(change.difference()).isEqualTo("3");
      assertThat(change.changeRate()).isNull();
      assertThat(change.reason()).isEqualTo("BASE_PRICE_ZERO");
      assertThat(change.previousEvidenceIds()).containsExactly("1010");
      assertThat(change.currentEvidenceIds()).containsExactly("1011");
      assertThat(change.krwDecomposition().priceEffectKrw()).isEqualTo("3900");
      assertThat(change.krwDecomposition().exchangeEffectKrw()).isEqualTo("300");
      assertThat(first.items().get(1).changeRate()).isNull();
      assertThat(first.items().get(1).krwDecomposition().status()).isEqualTo("NOT_APPLICABLE");

      var second = service.list(request(start, now, "west", 2, first.nextCursor()));
      assertThat(second.asOf()).isEqualTo(first.asOf());
      assertThat(second.items())
          .extracting(PriceChangeDto::eventType)
          .containsExactly("BECAME_PUBLIC", "FIRST_OBSERVATION");
      assertThat(second.items().get(0).lastUndisclosedEvidenceIds()).containsExactly("1018");
      assertThat(second.items().get(0).lastUndisclosedEvidence()).hasSize(1);
      assertThat(second.items().get(1).previousObservationId()).isNull();
      assertThat(second.nextCursor()).isNull();
      assertThatThrownBy(() -> service.list(request(start, now, "east", 2, first.nextCursor())))
          .isInstanceOf(InvalidRequestException.class);
    } finally {
      ds.destroy();
    }
  }

  private static MarketChangesRequestDto request(
      OffsetDateTime from, OffsetDateTime to, String region, int limit, String cursor) {
    return new MarketChangesRequestDto(
        1, from, to, List.of(1L), "us", region, "on_demand", true, limit, cursor);
  }

  private static void createTables(JdbcTemplate jdbc) {
    jdbc.execute(
        "CREATE TEMP TABLE product(product_id bigint, gpu_model_id bigint, provider_id bigint,"
            + " country_code text, region_code text)");
    jdbc.execute("CREATE TEMP TABLE offer(offer_id bigint, product_id bigint, contract_type text)");
    jdbc.execute(
        """
        CREATE TEMP TABLE collection_check(check_id bigint, offer_id bigint, checked_at timestamptz,
          processing_status text, disclosure_status text, check_scope text, scope_completed boolean)
        """);
    jdbc.execute(
        """
CREATE TEMP TABLE price_observation(observation_id bigint, offer_id bigint, check_id bigint,
  collected_at timestamptz, price_certainty text, original_currency text, original_time_unit text,
  price_applies_to text, corrects_observation_id bigint)
""");
    jdbc.execute("CREATE TEMP TABLE normalization_rule(rule_id bigint, rule_version text)");
    jdbc.execute(
        """
        CREATE TEMP TABLE price_normalization(normalization_id bigint, observation_id bigint,
          rule_id bigint, result_status text, effective_usd_gpu_hour numeric)
        """);
    jdbc.execute(
        """
        CREATE TEMP TABLE source_evidence(check_id bigint, evidence_id bigint, source_id bigint,
          evidence_role text, primary_verified boolean, source_url text, captured_at timestamptz)
        """);
    jdbc.execute(
        "CREATE TEMP TABLE data_source(source_id bigint, source_name text, base_url text)");
    jdbc.execute(
        """
        CREATE TEMP TABLE exchange_rate(exchange_rate_id bigint, source_id bigint, rate numeric,
          reference_date date, base_currency text, quote_currency text, source_url text)
        """);
    jdbc.execute(
        """
        CREATE TEMP TABLE krw_conversion(normalization_id bigint, exchange_rate_id bigint,
          calculated_at timestamptz, effective_krw_gpu_hour numeric)
        """);
  }

  private static void seed(JdbcTemplate jdbc) {
    jdbc.update("INSERT INTO product VALUES (1,1,1,'US','west')");
    jdbc.update("INSERT INTO offer VALUES (1,1,'on_demand'),(2,1,'on_demand'),(3,1,'on_demand')");
    jdbc.update(
        """
        INSERT INTO price_observation VALUES
          (10,1,10,now()-interval '48 hours','firm','USD','hour','gpu',NULL),
          (11,1,11,now()-interval '24 hours','firm','USD','hour','gpu',NULL),
          (12,1,12,now()-interval '22 hours','firm','USD','contract','gpu',NULL),
          (13,1,13,now()-interval '21 hours','firm','USD','contract','gpu',NULL),
          (14,1,14,now()-interval '20 hours','firm','USD','contract','gpu',13),
          (15,2,15,now()-interval '19 hours','firm','USD','hour','gpu',NULL),
          (16,3,16,now()-interval '18 hours','firm','USD','hour','gpu',NULL)
        """);
    jdbc.update(
        """
INSERT INTO collection_check
  SELECT check_id,offer_id,collected_at,'success','public','offer',true FROM price_observation
""");
    jdbc.update(
        """
        INSERT INTO collection_check VALUES
          (18,2,now()-interval '20 hours','success','not_disclosed','offer',true)
        """);
    jdbc.update(
        """
INSERT INTO source_evidence
  SELECT check_id,1000+check_id,1,'official',true,'https://example.com/public-price',checked_at
  FROM collection_check
""");
    jdbc.update("INSERT INTO data_source VALUES (1,'한국수출입은행','https://oapi.koreaexim.go.kr')");
    jdbc.update("INSERT INTO normalization_rule VALUES (1,'v1')");
    jdbc.update(
        """
INSERT INTO price_normalization VALUES
  (110,10,1,'success',0),(111,11,1,'success',3),(112,12,1,'success',4),
  (113,13,1,'success',5),(114,14,1,'success',4),(115,15,1,'success',6),(116,16,1,'success',7)
""");
    jdbc.update(
        """
        INSERT INTO exchange_rate VALUES
          (1,1,1300,current_date-3,'USD','KRW',NULL),(2,1,1400,current_date-2,'USD','KRW',NULL)
        """);
    jdbc.update(
        """
        INSERT INTO exchange_rate VALUES
          (3,1,9999,current_date-3,'USD','KRW','https://example.com/?source=koreaexim.go.kr')
        """);
    jdbc.update(
        "INSERT INTO krw_conversion VALUES (110,1,now(),0),(111,2,now(),4200),(110,3,now(),0)");
  }
}
