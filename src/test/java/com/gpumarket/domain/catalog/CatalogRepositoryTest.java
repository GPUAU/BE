package com.gpumarket.domain.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.gpumarket.domain.catalog.repository.CatalogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CatalogRepositoryTest {
  private CatalogRepository repository;
  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    var ds =
        new DriverManagerDataSource(
            "jdbc:h2:mem:catalog;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
    jdbc = new JdbcTemplate(ds);
    jdbc.execute("DROP ALL OBJECTS");
    jdbc.execute(
        "CREATE TABLE provider (provider_id BIGINT PRIMARY KEY, provider_name TEXT NOT NULL,"
            + " provider_type TEXT NOT NULL)");
    jdbc.execute(
        "CREATE TABLE gpu_model (gpu_model_id BIGINT PRIMARY KEY, model_name TEXT NOT NULL,"
            + " generation TEXT, release_date DATE, release_source_url TEXT)");
    jdbc.execute(
        "CREATE TABLE product (provider_id BIGINT NOT NULL, country_code TEXT, region_code TEXT)");
    repository = new CatalogRepository(jdbc);
  }

  @Test
  void returnsEmptyListsWhenNothingWasCollected() {
    var result = repository.findCatalog();
    assertThat(result.providers()).isEmpty();
    assertThat(result.models()).isEmpty();
    assertThat(result.regions()).isEmpty();
    assertThat(result.generations()).isEmpty();
  }

  @Test
  void mapsStoredProviderTypesToApiTypes() {
    jdbc.update(
        "INSERT INTO provider VALUES (1, 'AWS', 'hyperscaler'), (2, 'Neo', 'gpu_specialist'), (3,"
            + " 'Other', 'unknown')");
    assertThat(repository.findCatalog().providers())
        .extracting("type")
        .containsExactly("GENERAL_CLOUD", "GPU_SPECIALIST", "UNKNOWN");
  }

  @Test
  void returnsDistinctRegionsAndPreservesMissingValuesAndStringIds() {
    jdbc.update("INSERT INTO provider VALUES (9007199254740993, 'AWS', 'GENERAL_CLOUD')");
    jdbc.update("INSERT INTO gpu_model VALUES (1, 'B200', 'Blackwell', NULL, NULL)");
    jdbc.update("INSERT INTO gpu_model VALUES (2, 'B300', 'Blackwell', NULL, NULL)");
    jdbc.update(
        "INSERT INTO product VALUES (9007199254740993, 'US', 'us-east-1'), (9007199254740993, 'US',"
            + " 'us-east-1'), (9007199254740993, NULL, NULL)");

    var result = repository.findCatalog();
    assertThat(result.providers().get(0).id()).isEqualTo("9007199254740993");
    assertThat(result.models()).hasSize(2);
    assertThat(result.models().get(0).releaseDate()).isNull();
    assertThat(result.generations()).containsExactly("Blackwell");
    assertThat(result.regions()).hasSize(2);
    assertThat(result.regions()).anyMatch(r -> r.countryCode() == null && r.regionCode() == null);
    assertThat(result.contractTypes()).contains("ON_DEMAND", "SPOT", "COMMITMENT");
  }
}
