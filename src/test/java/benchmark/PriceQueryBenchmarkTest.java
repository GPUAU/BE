package benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gpumarket.GpuMarketApplication;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Optional read-only local experiment, not a production endpoint or entity model. */
@SpringBootTest(
    classes = GpuMarketApplication.class,
    properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.jpa.open-in-view=false"})
@EntityScan(basePackageClasses = PriceQueryBenchmarkTest.class)
@EnabledIfEnvironmentVariable(named = "RUN_PRICE_BENCHMARK", matches = "true")
class PriceQueryBenchmarkTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired EntityManager em;

  @Entity(name = "BenchNormalization")
  @Table(name = "price_normalization")
  static class Normalization {
    @Id
    @Column(name = "normalization_id")
    Long id;

    @Column(name = "effective_usd_gpu_hour")
    BigDecimal price;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "observation_id")
    Observation observation;
  }

  @Entity(name = "BenchObservation")
  @Table(name = "price_observation")
  static class Observation {
    @Id
    @Column(name = "observation_id")
    Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offer_id")
    Offer offer;
  }

  @Entity(name = "BenchOffer")
  @Table(name = "offer")
  static class Offer {
    @Id
    @Column(name = "offer_id")
    Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    Product product;
  }

  @Entity(name = "BenchProduct")
  @Table(name = "product")
  static class Product {
    @Id
    @Column(name = "product_id")
    Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_id")
    Provider provider;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gpu_model_id")
    Model model;
  }

  @Entity(name = "BenchProvider")
  @Table(name = "provider")
  static class Provider {
    @Id
    @Column(name = "provider_id")
    Long id;

    @Column(name = "provider_name")
    String name;
  }

  @Entity(name = "BenchModel")
  @Table(name = "gpu_model")
  static class Model {
    @Id
    @Column(name = "gpu_model_id")
    Long id;

    @Column(name = "model_name")
    String name;
  }

  record Row(
      long normalization,
      long observation,
      long offer,
      long product,
      String provider,
      String model,
      BigDecimal price) {}

  private static final String SQL =
      "SELECT"
          + " n.normalization_id,o.observation_id,f.offer_id,p.product_id,v.provider_name,g.model_name,n.effective_usd_gpu_hour"
          + " FROM price_normalization n JOIN price_observation o ON"
          + " o.observation_id=n.observation_id JOIN offer f ON f.offer_id=o.offer_id JOIN product"
          + " p ON p.product_id=f.product_id JOIN provider v ON v.provider_id=p.provider_id JOIN"
          + " gpu_model g ON g.gpu_model_id=p.gpu_model_id ORDER BY n.normalization_id";
  private static final String JPQL =
      "select n from BenchNormalization n join fetch n.observation o join fetch o.offer f join"
          + " fetch f.product p join fetch p.provider join fetch p.model order by n.id";

  List<Row> sql() {
    return jdbc.query(
        SQL,
        (rs, i) ->
            new Row(
                rs.getLong(1),
                rs.getLong(2),
                rs.getLong(3),
                rs.getLong(4),
                rs.getString(5),
                rs.getString(6),
                rs.getBigDecimal(7)));
  }

  List<Row> jpa() {
    em.clear();
    List<Normalization> values =
        em.createQuery(JPQL, Normalization.class)
            .setHint("org.hibernate.cacheable", false)
            .getResultList();
    return values.stream()
        .map(
            n ->
                new Row(
                    n.id,
                    n.observation.id,
                    n.observation.offer.id,
                    n.observation.offer.product.id,
                    n.observation.offer.product.provider.name,
                    n.observation.offer.product.model.name,
                    n.price))
        .toList();
  }

  @Test
  @Transactional
  void compareIdenticalPriceRows() {
    List<Row> a = sql(), b = jpa();
    assertEquals(a, b);
    System.out.println("BENCH rows=" + a.size() + " equal=true warmups=3 iterations=8");
    for (int i = 0; i < 3; i++) {
      sql();
      jpa();
    }
    for (int i = 0; i < 8; i++) {
      long start = System.nanoTime();
      if (i % 2 == 0) {
        a = sql();
        long mid = System.nanoTime();
        b = jpa();
        long end = System.nanoTime();
        System.out.printf(
            "BENCH %d JDBC %.2f ms JPA %.2f ms%n", i + 1, (mid - start) / 1e6, (end - mid) / 1e6);
      } else {
        b = jpa();
        long mid = System.nanoTime();
        a = sql();
        long end = System.nanoTime();
        System.out.printf(
            "BENCH %d JDBC %.2f ms JPA %.2f ms%n", i + 1, (end - mid) / 1e6, (mid - start) / 1e6);
      }
      assertEquals(a, b);
    }
  }
}
