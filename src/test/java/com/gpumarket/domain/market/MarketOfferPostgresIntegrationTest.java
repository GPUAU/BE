package com.gpumarket.domain.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.gpumarket.domain.market.dto.PublicPriceLookupDto.PublicPriceDto;
import com.gpumarket.domain.market.repository.MarketOfferRepository;
import com.gpumarket.domain.market.service.MarketOfferService;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class MarketOfferPostgresIntegrationTest {
  @Test
  void findsRelevantDisclosureWithoutReplacingTheLatestOfferCheck() {
    var ds =
        new SingleConnectionDataSource(
            System.getenv("TEST_POSTGRES_URL"), System.getenv("TEST_POSTGRES_USER"), "", true);
    try {
      var jdbc = new JdbcTemplate(ds);
      createTables(jdbc);
      jdbc.update("INSERT INTO provider VALUES (1, 'AWS', 'hyperscaler')");
      jdbc.update("INSERT INTO gpu_model VALUES (1, 'B300', 'Blackwell')");
      jdbc.update(
          "INSERT INTO product (product_id, provider_id, gpu_model_id, region_code)"
              + " VALUES (1,1,1,'us-east-1'),(2,1,1,'us-east-1')");
      jdbc.update(
          "INSERT INTO offer VALUES (1,1,'on_demand','On-demand'),(2,2,'on_demand','On-demand')");
      jdbc.update(
          """
          INSERT INTO collection_target VALUES
            (1,1,1,1,'us-east-1','on_demand'),
            (2,1,1,1,'us-west-2','on_demand'),
            (3,1,1,2,'us-east-1','on_demand'),
            (4,1,1,1,'us-east-1','spot'),
            (5,1,1,1,NULL,'on_demand'),
            (6,1,2,1,'us-east-1','on_demand')
          """);
      jdbc.update(
          """
          INSERT INTO collection_check VALUES
            (10,1,1,now()-interval '4 hours','success','public',NULL,'offer',true),
            (11,1,1,now()-interval '5 minutes','collection_failed','unknown',NULL,'offer',false),
            (12,2,NULL,now()-interval '1 hour','not_found','unknown',NULL,'target',true),
            (13,3,NULL,now()-interval '1 hour','not_found','unknown',NULL,'target',true),
            (14,4,NULL,now()-interval '1 hour','not_found','unknown',NULL,'target',true),
            (15,5,NULL,now()-interval '1 hour','not_found','unknown',NULL,'target',true),
            (16,1,NULL,now()-interval '1 hour','success','not_disclosed',NULL,'target',false),
            (17,6,NULL,now()-interval '1 hour','not_found','unknown',NULL,'target',true),
            (20,1,2,now()-interval '3 hours','success','public',NULL,'offer',true)
          """);
      jdbc.update(
          "INSERT INTO price_observation VALUES (1,10,1,now()-interval '4"
              + " hours',NULL,'firm','$4'), (2,20,2,now()-interval '3 hours',NULL,'firm','$5')");
      var repository = new MarketOfferRepository(new NamedParameterJdbcTemplate(jdbc));
      var asOf = OffsetDateTime.now().withNano(0);
      var query = Map.<String, Object>of("offerId", 1L, "asOf", asOf);
      var otherOffer = Map.<String, Object>of("offerId", 2L, "asOf", asOf);
      assertThat(repository.offers(query).get(0))
          .containsEntry("processing_status", "collection_failed")
          .containsEntry("disclosure_status", "public")
          .containsEntry("disclosure_check_id", 10L);

      jdbc.update(
          """
          INSERT INTO collection_check VALUES
            (18,1,NULL,now()-interval '30 minutes','not_found','unknown',NULL,'target',true)
          """);
      jdbc.update(
          "INSERT INTO source_evidence VALUES (18,1,'official',true,'unstructured history')");
      assertThat(repository.offers(query).get(0)).containsEntry("disclosure_check_id", 10L);
      assertThat(repository.offers(otherOffer).get(0)).containsEntry("disclosure_check_id", 20L);

      addVerifiedScope(jdbc, 18, "1", asOf.minusHours(2), asOf);
      assertThat(repository.offers(query).get(0)).containsEntry("disclosure_check_id", 10L);

      addVerifiedScope(jdbc, 18, "1", asOf, asOf.plusHours(1));
      assertThat(repository.offers(query).get(0))
          .containsEntry("processing_status", "collection_failed")
          .containsEntry("disclosure_processing_status", "not_found")
          .containsEntry("disclosure_check_id", 18L)
          .containsEntry("observation_id", 1L);
      assertThat(repository.offers(otherOffer).get(0))
          .containsEntry("disclosure_check_id", 20L)
          .containsEntry("raw_price_text", "$5");

      jdbc.update(
          """
          INSERT INTO collection_check VALUES
            (19,1,1,now()-interval '1 minute','success','public',NULL,'offer',true)
          """);
      assertThat(repository.offers(query).get(0))
          .containsEntry("processing_status", "success")
          .containsEntry("disclosure_status", "public")
          .containsEntry("disclosure_check_id", 19L);
    } finally {
      ds.destroy();
    }
  }

  @Test
  void findsPublicPriceAfterIneligibleOffersAndRejectsNonPriceEvidence() {
    var ds =
        new SingleConnectionDataSource(
            System.getenv("TEST_POSTGRES_URL"), System.getenv("TEST_POSTGRES_USER"), "", true);
    try {
      var jdbc = new JdbcTemplate(ds);
      createTables(jdbc);
      jdbc.execute(
          """
ALTER TABLE source_evidence
  ADD COLUMN evidence_id bigint GENERATED ALWAYS AS IDENTITY,
  ADD COLUMN source_url text,
  ADD COLUMN captured_at timestamptz,
  ADD COLUMN verified_at timestamptz,
  ADD COLUMN source_version text;
CREATE TEMP TABLE data_source(source_id bigint, source_name text, source_type text);
CREATE TEMP TABLE price_component(component_id bigint, observation_id bigint);
INSERT INTO data_source VALUES (1, 'AWS Pricing', 'official_api'),
  (2, 'Marketplace', 'aggregator');
INSERT INTO provider VALUES (1, 'AWS', 'hyperscaler');
INSERT INTO gpu_model VALUES (1, 'B300', 'Blackwell');
INSERT INTO product (product_id, provider_id, gpu_model_id, region_code)
  VALUES (1, 1, 1, 'us-east-1');
INSERT INTO offer
  SELECT id, 1, 'on_demand', 'On-demand' FROM generate_series(1, 105) AS id;
INSERT INTO collection_target VALUES (1, 1, 1, 1, 'us-east-1', 'on_demand');
INSERT INTO collection_check
  SELECT id, 1, id, now() - interval '1 hour', 'success',
    CASE WHEN id = 1 THEN 'login_required' ELSE 'public' END, NULL, 'offer', true
  FROM generate_series(1, 105) AS id;
INSERT INTO price_observation
  SELECT id, id, id, now() - interval '1 hour', NULL, 'firm', '$8'
  FROM generate_series(1, 105) AS id;
INSERT INTO normalization_rule VALUES (1, 'v1', 730, 'pricing', 'decimal');
INSERT INTO price_normalization
  SELECT id, id, 1, 8, 8, 'success', NULL, NULL
  FROM generate_series(1, 105) AS id;
INSERT INTO source_evidence
  (check_id, source_id, evidence_role, primary_verified, source_url, captured_at, verified_at)
VALUES
  (1, 1, 'official', false, 'https://example.org/private', now(), NULL),
  (102, 1, 'spec', false, 'https://example.org/spec', now(), NULL),
  (103, 1, 'official', false, 'https://example.org/future', now()+interval '1 hour', NULL),
  (104, 2, 'aggregator', true, 'https://example.org/list', now(), now()+interval '1 hour'),
  (105, 1, 'official', false, 'https://example.org/price', now(), NULL);
""");
      var repository = new MarketOfferRepository(new NamedParameterJdbcTemplate(jdbc));
      var prices =
          new MarketOfferService(repository, null)
              .publicPrices(Map.of("modelId", "1", "contractType", "ON_DEMAND"));
      assertThat(prices.items()).extracting(PublicPriceDto::offerId).containsExactly("105");
      assertThat(prices.items().get(0).sources().get(0))
          .containsEntry("sourceUrl", "https://example.org/price");
      assertThat(prices.limited()).isFalse();
    } finally {
      ds.destroy();
    }
  }

  private static void addVerifiedScope(
      JdbcTemplate jdbc, long checkId, String offerId, OffsetDateTime from, OffsetDateTime to) {
    jdbc.update(
        """
        INSERT INTO source_evidence
        SELECT ?, 1, 'official', true, jsonb_build_object('offerAvailability',
          jsonb_build_object('offerId', ?::text, 'validFromEpoch', ?::bigint,
                             'validToEpoch', ?::bigint))::text
        """,
        checkId,
        offerId,
        from.toEpochSecond(),
        to.toEpochSecond());
  }

  private static void createTables(JdbcTemplate jdbc) {
    jdbc.execute(
        "CREATE TEMP TABLE provider(provider_id bigint, provider_name text, provider_type text)");
    jdbc.execute(
        "CREATE TEMP TABLE gpu_model(gpu_model_id bigint, model_name text, generation text)");
    jdbc.execute(
        """
        CREATE TEMP TABLE product(product_id bigint, provider_id bigint, gpu_model_id bigint,
          region_code text, sku text, original_product_name text, original_model_name text,
          system_type text, service_option text, gpu_count int, vram_gb_per_gpu numeric,
          vcpu_count numeric, ram_gb numeric, spec_scope text, storage_description text,
          intra_node_interconnect text, interconnect_status text, inter_node_network text,
          country_code text, configuration_version int)
        """);
    jdbc.execute(
        "CREATE TEMP TABLE offer(offer_id bigint, product_id bigint, contract_type text,"
            + " contract_product_type text)");
    jdbc.execute(
        """
CREATE TEMP TABLE collection_target(target_id bigint, provider_id bigint, gpu_model_id bigint,
  source_id bigint, region_filter text, contract_filter text)
""");
    jdbc.execute(
        """
        CREATE TEMP TABLE collection_check(check_id bigint, target_id bigint, offer_id bigint,
          checked_at timestamptz, processing_status text, disclosure_status text, reason text,
          check_scope text, scope_completed boolean)
        """);
    jdbc.execute(
        """
        CREATE TEMP TABLE source_evidence(check_id bigint, source_id bigint,
          evidence_role text, primary_verified boolean, excerpt text)
        """);
    jdbc.execute(
        """
CREATE TEMP TABLE price_observation(observation_id bigint, check_id bigint, offer_id bigint,
  collected_at timestamptz, effective_from timestamptz, price_certainty text, raw_price_text text)
""");
    jdbc.execute(
        """
        CREATE TEMP TABLE normalization_rule(rule_id bigint, rule_version text, monthly_hours int,
          formula_description text, precision_policy text)
        """);
    jdbc.execute(
        """
        CREATE TEMP TABLE price_normalization(normalization_id bigint, observation_id bigint,
          rule_id bigint, nominal_usd_gpu_hour numeric, effective_usd_gpu_hour numeric,
          result_status text, reason text, calculation_inputs text)
        """);
  }
}
