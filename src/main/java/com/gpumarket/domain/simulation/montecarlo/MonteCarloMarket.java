package com.gpumarket.domain.simulation.montecarlo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/** Generates indexed market price paths and summarizes their distributions. */
public final class MonteCarloMarket {
  static final int ANCHOR_MONTH = 12;
  private static final String SAMPLE_RESOURCE = "/montecarlo/b300-sample-price-history.csv";
  private static final String CSV_HEADER = "month,price_usd_per_gpu_hour";

  private MonteCarloMarket() {}

  public record MonthlyRange(
      int month,
      double p10,
      double p25,
      double p50,
      double p75,
      double p90,
      double probabilityAtOrBelowCurrent) {}

  public record HistoryPoint(String month, double price) {}

  public record HistoryEvidence(
      String firstMonth,
      String lastMonth,
      int nMonths,
      double firstPrice,
      double lastPrice,
      double monthlyMeanChange,
      double annualizedTrend,
      double monthlyVolatility,
      String worstMonth,
      double worstMonthChange,
      double downMonthShare) {}

  public record MarketHistory(
      List<HistoryPoint> points, double[] returns, HistoryEvidence evidence, String historyHash) {
    public MarketHistory {
      points = List.copyOf(points);
      returns = returns.clone();
    }

    @Override
    public double[] returns() {
      return returns.clone();
    }

    public double lastPrice() {
      return evidence.lastPrice();
    }
  }

  public static MarketHistory loadSampleHistory() {
    try (var source = MonteCarloMarket.class.getResourceAsStream(SAMPLE_RESOURCE)) {
      if (source == null) {
        throw new IllegalStateException("Missing Monte Carlo sample history");
      }

      byte[] rawHistory = source.readAllBytes();
      List<HistoryPoint> points = parseHistory(rawHistory);
      double[] returns = new double[points.size() - 1];
      for (int i = 0; i < returns.length; i++) {
        returns[i] = Math.log(points.get(i + 1).price() / points.get(i).price());
      }

      return new MarketHistory(
          points, returns, summarizeHistory(points, returns), sha256(rawHistory));
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot read Monte Carlo sample history", exception);
    }
  }

  private static List<HistoryPoint> parseHistory(byte[] rawHistory) {
    List<String> lines = new String(rawHistory, StandardCharsets.UTF_8).lines().toList();
    if (lines.size() < 4 || !lines.get(0).equals(CSV_HEADER)) {
      throw new IllegalStateException("Invalid Monte Carlo sample history header or length");
    }

    List<HistoryPoint> points = new ArrayList<>(lines.size() - 1);
    YearMonth previous = null;
    for (String line : lines.subList(1, lines.size())) {
      String[] columns = line.split(",", -1);
      if (columns.length != 2) {
        throw new IllegalStateException("Invalid Monte Carlo sample history row");
      }

      YearMonth month = YearMonth.parse(columns[0].strip());
      double price = Double.parseDouble(columns[1]);
      if ((previous != null && !month.isAfter(previous)) || !Double.isFinite(price) || price <= 0) {
        throw new IllegalStateException("Invalid sample history month order or price");
      }

      points.add(new HistoryPoint(month.toString(), price));
      previous = month;
    }
    return points;
  }

  private static HistoryEvidence summarizeHistory(List<HistoryPoint> points, double[] returns) {
    double mean = Arrays.stream(returns).average().orElseThrow();
    double sumSquaredDifferences = 0;
    int negativeMonths = 0;
    int worst = 0;
    for (int i = 0; i < returns.length; i++) {
      double difference = returns[i] - mean;
      sumSquaredDifferences += difference * difference;
      if (returns[i] < 0) {
        negativeMonths++;
      }
      if (returns[i] < returns[worst]) {
        worst = i;
      }
    }

    HistoryPoint first = points.get(0);
    HistoryPoint last = points.get(points.size() - 1);
    return new HistoryEvidence(
        first.month(),
        last.month(),
        points.size(),
        first.price(),
        last.price(),
        Math.exp(mean) - 1,
        Math.exp(ANCHOR_MONTH * mean) - 1,
        Math.sqrt(sumSquaredDifferences / (returns.length - 1)),
        points.get(worst + 1).month(),
        Math.exp(returns[worst]) - 1,
        (double) negativeMonths / returns.length);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  public static double[][] generatePaths(
      MonteCarloConfig.Simulation simulation,
      int months,
      MonteCarloConfig.Post12Trend trend,
      double[] returns) {
    return generatePaths(
        simulation, months, trend, returns, new SplittableRandom(simulation.seed()));
  }

  static double[][] generatePaths(
      MonteCarloConfig.Simulation simulation,
      int months,
      MonteCarloConfig.Post12Trend trend,
      double[] returns,
      RandomGenerator random) {
    double mean = Arrays.stream(returns).average().orElseThrow();
    double restartProbability = 1.0 / simulation.meanBlockLength();
    double jumpProbability = simulation.jumpProbPerMonth();
    List<Double> jumpSize = simulation.jumpSizeTri();

    double[] meanTrendRemoval = new double[months + 1];
    for (int month = ANCHOR_MONTH + 1; month <= months; month++) {
      double retainedTrend =
          switch (trend) {
            case KEEP -> 1;
            case EASE -> Math.pow(0.5, (month - ANCHOR_MONTH) / 12.0);
            case FLAT -> 0;
          };
      meanTrendRemoval[month] = mean * (1 - retainedTrend);
    }

    double[][] paths = new double[simulation.nPaths()][months + 1];
    for (double[] path : paths) {
      double logLevel = 0;
      int blockIndex = -1;
      path[0] = 1;

      for (int month = 1; month <= months; month++) {
        if (blockIndex < 0 || random.nextDouble() < restartProbability) {
          blockIndex = random.nextInt(returns.length);
        } else {
          blockIndex = (blockIndex + 1) % returns.length;
        }

        logLevel += returns[blockIndex] - meanTrendRemoval[month];
        if (jumpProbability > 0 && random.nextDouble() < jumpProbability) {
          logLevel +=
              Math.log1p(triangular(jumpSize.get(0), jumpSize.get(1), jumpSize.get(2), random));
        }
        path[month] = Math.exp(logLevel);
      }
    }
    return paths;
  }

  static double triangular(double minimum, double mode, double maximum, RandomGenerator random) {
    double weight = random.nextDouble();
    if (minimum == maximum) {
      return minimum;
    }

    double peak = (mode - minimum) / (maximum - minimum);
    if (weight > peak) {
      return maximum + (minimum - maximum) * Math.sqrt((1 - weight) * (1 - peak));
    }
    return minimum + (maximum - minimum) * Math.sqrt(weight * peak);
  }

  public static List<MonthlyRange> marketRanges(double[][] paths, double lastPrice, int months) {
    List<MonthlyRange> ranges = new ArrayList<>(months);
    for (int month = 1; month <= months; month++) {
      double[] prices = monthPrices(paths, lastPrice, month);
      Arrays.sort(prices);
      ranges.add(
          new MonthlyRange(
              month,
              quantile(prices, 0.10),
              quantile(prices, 0.25),
              quantile(prices, 0.50),
              quantile(prices, 0.75),
              quantile(prices, 0.90),
              probabilityAtOrBelow(prices, lastPrice)));
    }
    return ranges;
  }

  static double[] monthPrices(double[][] paths, double lastPrice, int month) {
    double[] prices = new double[paths.length];
    for (int i = 0; i < paths.length; i++) {
      prices[i] = lastPrice * paths[i][month];
    }
    return prices;
  }

  public static double quantile(double[] sortedValues, double q) {
    if (sortedValues.length == 0) {
      throw new IllegalArgumentException("A quantile needs at least one value");
    }
    double position = q * (sortedValues.length - 1);
    int lower = (int) position;
    int upper = Math.min(lower + 1, sortedValues.length - 1);
    return sortedValues[lower] + (sortedValues[upper] - sortedValues[lower]) * (position - lower);
  }

  public static double probabilityAtOrBelow(double[] sortedValues, double value) {
    if (sortedValues.length == 0) {
      throw new IllegalArgumentException("A probability needs at least one value");
    }
    int low = 0;
    int high = sortedValues.length;
    while (low < high) {
      int middle = (low + high) >>> 1;
      if (sortedValues[middle] <= value) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    return (double) low / sortedValues.length;
  }
}
