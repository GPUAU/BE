package com.gpumarket.domain.agent.service;

import com.gpumarket.domain.agent.dto.AgentMessageRequestDto;
import com.gpumarket.domain.agent.dto.AgentMessageResponseDto;
import com.gpumarket.domain.agent.dto.AgentMessageResponseDto.AgentResultDto;
import com.gpumarket.domain.agent.dto.AgentMessageResponseDto.SampleCountsDto;
import com.gpumarket.domain.agent.dto.PublicContextDto;
import com.gpumarket.domain.agent.service.AgentSessionStore.Conditions;
import com.gpumarket.domain.catalog.dto.CatalogResponseDto.GpuModelDto;
import com.gpumarket.domain.catalog.dto.CatalogResponseDto.ProviderDto;
import com.gpumarket.domain.catalog.service.CatalogService;
import com.gpumarket.domain.market.dto.PublicPriceLookupDto;
import com.gpumarket.domain.market.dto.PublicPriceLookupDto.PublicPriceDto;
import com.gpumarket.domain.market.service.MarketOfferService;
import java.text.Normalizer;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class AgentMessageService {
  private static final String DISCLAIMER = "수집한 공개 가격이며 실제 거래가와 다를 수 있습니다.";
  private static final Pattern SENSITIVE =
      Pattern.compile(
          "원가|구매가|매입가|마진|비밀번호|비공개|내부\\s*계약|협상가|비밀키|api["
              + " _-]*key|password|secret|sk-[a-z0-9_-]{8,}|\\birr\\b"
              + "|\\b(?:internal|confidential)\\s+(?:(?:purchase|contract)\\s+)?(?:costs?|prices?|rates?)\\b|\\b(?:purchase"
              + " price|profit margins?)\\b");
  private static final Pattern PRICE = Pattern.compile("가격|단가|시세|\\bprices?\\b|\\brates?\\b");
  private static final Pattern OTHER_INTENT =
      Pattern.compile(
          "예측|전망|추이|이력|변동|원인|할인|평균|중앙값|최저|최고|내년|내일|내달|작년"
              + "|지난|다음\\s*(?:주|달|월|해)|어제|그제|(?:시간|일|주|개월|년)\\s*(?:후|뒤|전)"
              + "|\\b(?:forecast|history|historical|trend|predict|average|median|cheapest"
              + "|lowest|highest|tomorrow|yesterday|future|discounts?)\\b"
              + "|\\b(?:last|next)\\s+(?:week|month|year)\\b"
              + "|(?<![a-z0-9])20[0-9]{2}(?:[-/.][0-9]{1,2}|\\s*년)");
  private static final Pattern ON_DEMAND = Pattern.compile("온\\s*디맨드|온\\s*디멘드|on[ _-]*demand");
  private static final Pattern OTHER_CONTRACT =
      Pattern.compile("스팟|약정|\\bspot\\b|\\bcommitment\\b|\\breserved\\b");
  private static final Pattern GPU_TOKEN =
      Pattern.compile("(?<![a-z0-9])(?:gb|vr|[habvl])[0-9]{2,4}(?![a-z0-9])");
  private static final Pattern REGION =
      Pattern.compile("\\b[a-z]{2}(?:-[a-z]+)+-[0-9]+\\b|리전|서울|오리건|도쿄|버지니아|리전별");
  private static final Pattern NON_USD =
      Pattern.compile(
          "원화|원으로|유로|엔화|엔으로|위안|파운드|[₩€¥£]"
              + "|\\b(?:krw|eur|jpy|cny|gbp|cad|aud|euros?|yen|yuan|won|pounds?)\\b");
  private static final Pattern PER_GPU = Pattern.compile("\\bper\\s+1\\s+(?=gpus?\\b)");
  private static final Map<String, Pattern> SYSTEMS =
      Map.of(
          "INSTANCE", Pattern.compile("인스턴스|\\binstances?\\b"),
          "NODE", Pattern.compile("노드|\\bnodes?\\b"),
          "RACK_SCALE", Pattern.compile("랙|\\brack\\b"));
  private static final Map<String, Pattern> PROVIDER_TYPES =
      Map.of(
          "GENERAL_CLOUD", Pattern.compile("종합\\s*(?:클라우드|사업자)|\\bgeneral\\s+cloud\\b"),
          "GPU_SPECIALIST",
              Pattern.compile("(?:gpu\\s*)?(?:특화|전문)\\s*(?:클라우드|사업자)|\\bgpu\\s+specialists?\\b"));
  private static final Pattern FOLLOW_UP_FILLER =
      Pattern.compile(
          "\\s+|[!?,.…]|그럼|그러면|그\\s*중|이번에는|이번엔|대신|이\\s*조건으로|"
              + "같은\\s*조건으로|에서도|에서|으로|까지|쪽|[은는이가만에도로요]"
              + "|\\b(?:what about|how about|instead|only|and|in|for)\\b");
  private static final Pattern GPU_COUNT =
      Pattern.compile("(?<![a-z0-9])([0-9]{1,4})\\s*(?:장(?!\\s*당)|gpus?\\b)");
  private static final Map<String, Pattern> COUNTRY_ALIASES =
      Map.of(
          "KR", Pattern.compile("한국|국내|대한민국|\\bkorea\\b"),
          "US", Pattern.compile("미국|\\busa\\b|\\bu\\.s\\b"),
          "GB", Pattern.compile("영국|\\buk\\b"));
  private static final Map<String, Pattern> COUNTRIES =
      Arrays.stream(Locale.getISOCountries())
          .collect(Collectors.toUnmodifiableMap(code -> code, AgentMessageService::countryPattern));
  private static final PublicContextDto EMPTY =
      new PublicContextDto(null, null, null, null, null, null);
  private static final Conditions EMPTY_CONDITIONS = new Conditions(EMPTY, null, false);
  private static final DateTimeFormatter OBSERVED_AT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'KST'").withZone(ZoneId.of("Asia/Seoul"));

  private final CatalogService catalog;
  private final MarketOfferService offers;
  private final AgentSessionStore sessions;

  public AgentMessageService(
      CatalogService catalog, MarketOfferService offers, AgentSessionStore sessions) {
    this.catalog = catalog;
    this.offers = offers;
    this.sessions = sessions;
  }

  public AgentMessageResponseDto answer(AgentMessageRequestDto request) {
    String original = Normalizer.normalize(request.message(), Normalizer.Form.NFKC);
    String question = original.toLowerCase(Locale.ROOT);
    if (SENSITIVE.matcher(question).find()) {
      throw new SensitiveInput();
    }
    String sessionId = request.sessionId();
    if (sessionId == null) {
      Lookup lookup = lookup(request, EMPTY_CONDITIONS, original, question);
      String id = sessions.create(firstValue(lookup.conditions(), EMPTY_CONDITIONS));
      return lookup.response().withSession(id, List.of(), List.of());
    }

    var session = sessions.require(sessionId);
    synchronized (session) {
      Conditions previous = sessions.require(sessionId).conditions;
      Lookup lookup = lookup(request, previous, original, question);
      Conditions current = lookup.conditions();
      if (current == null) {
        sessions.complete(sessionId, previous);
        return lookup.response().withSession(sessionId, List.of(), List.of());
      }
      sessions.complete(sessionId, current);
      return followUpResponse(sessionId, lookup.response(), previous, current);
    }
  }

  public void deleteSession(String sessionId) {
    sessions.delete(sessionId);
  }

  private Lookup lookup(
      AgentMessageRequestDto request, Conditions previous, String original, String question) {
    var context = request.publicContext() == null ? EMPTY : request.publicContext();
    var remembered = previous.publicContext();
    boolean asksPrice = PRICE.matcher(question).find();
    if ((!asksPrice && remembered.contractType() == null)
        || OTHER_INTENT.matcher(question).find()) {
      return clarify(context, "공개 온디맨드 단가를 확인할 GPU 모델과 조건을 입력해 주세요.", "intent");
    }
    if (OTHER_CONTRACT.matcher(question).find()
        || (context.contractType() != null
            && !context.contractType().equalsIgnoreCase("ON_DEMAND"))) {
      return clarify(context, "현재 공개 가격 질의에서 지원하는 계약은 온디맨드입니다.", "contractType");
    }
    if (REGION.matcher(question).find()) {
      return clarify(context, "이 질의 화면에서는 국가 코드로 범위를 지정해 주세요.", "countryCode");
    }

    if (NON_USD.matcher(question).find()) {
      return clarify(context, "현재 달러 기준 GPU당 시간 단가를 조회합니다.", "currency");
    }

    var systems =
        SYSTEMS.entrySet().stream()
            .filter(item -> item.getValue().matcher(question).find())
            .map(Map.Entry::getKey)
            .toList();
    String systemType =
        systems.isEmpty()
            ? firstValue(upper(context.systemType()), remembered.systemType())
            : systems.get(0);
    if (systems.size() > 1 || conflict(context.systemType(), systemType)) {
      return clarify(context, "조회할 상품 구성이 여러 개거나 화면 선택과 다릅니다.", "systemType");
    }

    var available = catalog.getCatalog();
    var namedModels = mentionedModels(question, available.models());
    if (namedModels.size() > 1 || (namedModels.isEmpty() && GPU_TOKEN.matcher(question).find())) {
      return clarify(context, "조회할 GPU 모델 한 가지를 확인해 주세요.", "modelId");
    }

    String modelId =
        namedModels.isEmpty()
            ? firstValue(context.modelId(), remembered.modelId())
            : namedModels.get(0).id();
    if (conflict(context.modelId(), modelId)) {
      return clarify(context, "질문의 GPU 모델과 화면에서 선택한 GPU 모델이 다릅니다.", "modelId");
    }
    var nations =
        COUNTRIES.entrySet().stream()
            .filter(country -> country.getValue().matcher(original).find())
            .map(Map.Entry::getKey)
            .toList();
    String country =
        nations.isEmpty()
            ? firstValue(upper(context.countryCode()), remembered.countryCode())
            : nations.get(0);
    if (nations.size() > 1 || conflict(context.countryCode(), country)) {
      return clarify(context, "조회할 국가가 여러 개거나 화면 선택과 다릅니다.", "countryCode");
    }

    var count = GPU_COUNT.matcher(PER_GPU.matcher(question).replaceAll("per "));
    boolean mentionsGpuCount = count.find();
    Integer gpuCount = firstValue(context.gpuCount(), remembered.gpuCount());
    if (mentionsGpuCount) {
      int mentioned = Integer.parseInt(count.group(1));
      if (mentioned == 0
          || count.find()
          || (context.gpuCount() != null && context.gpuCount() != mentioned)) {
        return clarify(context, "조회할 상품의 GPU 장수를 확인해 주세요.", "gpuCount");
      }
      gpuCount = mentioned;
    }

    var namedProviders =
        available.providers().stream()
            .filter(provider -> mentions(question, provider.name()))
            .toList();
    if (namedProviders.size() > 1) {
      return clarify(context, "사업자 한 곳을 선택하거나 사업자 이름을 빼고 질문해 주세요.", "providerIds");
    }
    var namedTypes =
        PROVIDER_TYPES.entrySet().stream()
            .filter(item -> item.getValue().matcher(question).find())
            .map(Map.Entry::getKey)
            .toList();
    String requestedType = namedTypes.isEmpty() ? upper(context.providerType()) : namedTypes.get(0);
    if (namedTypes.size() > 1 || conflict(context.providerType(), requestedType)) {
      return clarify(context, "조회할 사업자 유형이 여러 개거나 화면 선택과 다릅니다.", "providerType");
    }

    String type = firstValue(requestedType, remembered.providerType());
    ProviderDto provider =
        namedProviders.isEmpty()
            ? available.providers().stream()
                .filter(item -> item.id().equals(previous.providerId()))
                .findFirst()
                .orElse(null)
            : namedProviders.get(0);
    if (provider != null && !"ALL".equals(type) && conflict(type, provider.type())) {
      if (namedProviders.isEmpty()) {
        provider = null;
      } else if (requestedType == null) {
        type = null;
      } else {
        return clarify(context, "질문의 사업자와 화면에서 선택한 사업자 유형이 다릅니다.", "providerType");
      }
    }

    String providerId = provider == null ? null : provider.id();
    boolean namesContract = ON_DEMAND.matcher(question).find();
    boolean assumesContract =
        context.contractType() == null
            && !namesContract
            && (remembered.contractType() == null || previous.assumedContract());
    var applied = new PublicContextDto(modelId, "ON_DEMAND", country, systemType, gpuCount, type);
    var conditions = new Conditions(applied, providerId, assumesContract);
    if (!asksPrice) {
      boolean namesCondition =
          !namedModels.isEmpty()
              || !nations.isEmpty()
              || !systems.isEmpty()
              || !namedProviders.isEmpty()
              || !namedTypes.isEmpty()
              || namesContract
              || mentionsGpuCount;
      if ((!namesCondition && conditions.equals(previous))
          || !isConditionFragment(original, nations, namedModels, namedProviders)) {
        return clarify(context, "확인할 공개 온디맨드 가격 조건을 입력해 주세요.", "intent");
      }
    }

    var assumptions = new ArrayList<String>();
    if (assumesContract) {
      assumptions.add("계약 유형을 온디맨드로 가정합니다.");
    }
    if (provider != null) {
      assumptions.add("조회 사업자: " + provider.name());
    }

    if (modelId == null) {
      // Retain the confirmed public filters until the user names the missing model.
      var clarification = clarify(applied, "조회할 GPU 모델을 알려 주세요.", "modelId", assumptions);
      return new Lookup(clarification.response(), conditions);
    }
    var model = available.models().stream().filter(item -> item.id().equals(modelId)).findFirst();
    if (model.isEmpty()) {
      return clarify(context, "조회할 GPU 모델을 알려 주세요.", "modelId");
    }

    var data = offers.publicPrices(filters(applied, providerId));
    if (data.limited()) {
      assumptions.add("조건에 맞는 전체 결과 중 일부만 표시합니다. 표본 수는 표시된 오퍼의 건수입니다.");
    }
    return new Lookup(reply(model.get(), applied, data, assumptions), conditions);
  }

  private static boolean isConditionFragment(
      String original,
      List<String> countries,
      List<GpuModelDto> models,
      List<ProviderDto> providers) {
    String remainder = original;
    for (String country : countries) {
      remainder = COUNTRIES.get(country).matcher(remainder).replaceAll(" ");
    }
    remainder = remainder.toLowerCase(Locale.ROOT);
    for (var model : models) {
      remainder = removeName(remainder, model.name());
    }
    for (var provider : providers) {
      remainder = removeName(remainder, provider.name());
    }
    for (Pattern pattern : SYSTEMS.values()) {
      remainder = pattern.matcher(remainder).replaceAll(" ");
    }
    for (Pattern pattern : PROVIDER_TYPES.values()) {
      remainder = pattern.matcher(remainder).replaceAll(" ");
    }
    remainder = GPU_COUNT.matcher(remainder).replaceAll(" ");
    remainder = ON_DEMAND.matcher(remainder).replaceAll(" ");
    return FOLLOW_UP_FILLER.matcher(remainder).replaceAll("").isEmpty();
  }

  private static String removeName(String question, String name) {
    return bounded(Pattern.quote(name.toLowerCase(Locale.ROOT))).matcher(question).replaceAll(" ");
  }

  private static Map<String, String> filters(PublicContextDto context, String providerId) {
    var filters = new LinkedHashMap<String, String>();
    filters.put("modelId", context.modelId());
    filters.put("contractType", context.contractType());
    if (context.countryCode() != null) {
      filters.put("countryCode", context.countryCode());
    }
    if (context.systemType() != null) {
      filters.put("systemType", context.systemType());
    }
    if (context.gpuCount() != null) {
      filters.put("gpuCount", context.gpuCount().toString());
    }
    if (context.providerType() != null) {
      filters.put("providerType", context.providerType());
    }
    if (providerId != null) {
      filters.put("providerIds", providerId);
    }
    return filters;
  }

  private static Lookup clarify(PublicContextDto context, String question, String field) {
    return clarify(context, question, field, List.of());
  }

  private static Lookup clarify(
      PublicContextDto context, String question, String field, List<String> assumptions) {
    var response =
        new AgentMessageResponseDto(
            null,
            "NEEDS_CLARIFICATION",
            question,
            context,
            List.copyOf(assumptions),
            List.of(),
            List.of(),
            List.of(field),
            List.of(),
            null,
            null,
            null,
            List.of(),
            null,
            "NOT_QUERIED",
            null,
            null,
            DISCLAIMER);
    return new Lookup(response, null);
  }

  private static AgentMessageResponseDto followUpResponse(
      String id, AgentMessageResponseDto response, Conditions before, Conditions after) {
    var previous = before.publicContext();
    var current = after.publicContext();
    var retained = new ArrayList<String>();
    var changed = new ArrayList<String>();
    compareCondition("modelId", previous.modelId(), current.modelId(), retained, changed);
    compareCondition(
        "contractType", previous.contractType(), current.contractType(), retained, changed);
    compareCondition(
        "countryCode", previous.countryCode(), current.countryCode(), retained, changed);
    compareCondition("systemType", previous.systemType(), current.systemType(), retained, changed);
    compareCondition("gpuCount", previous.gpuCount(), current.gpuCount(), retained, changed);
    compareCondition(
        "providerType", previous.providerType(), current.providerType(), retained, changed);
    compareCondition("providerIds", before.providerId(), after.providerId(), retained, changed);
    return response.withSession(id, List.copyOf(retained), List.copyOf(changed));
  }

  private static void compareCondition(
      String field, Object before, Object after, List<String> retained, List<String> changed) {
    if (!Objects.equals(before, after)) {
      changed.add(field);
    } else if (after != null) {
      retained.add(field);
    }
  }

  private static <T> T firstValue(T explicit, T remembered) {
    return explicit == null ? remembered : explicit;
  }

  private record Lookup(AgentMessageResponseDto response, Conditions conditions) {}

  private static AgentMessageResponseDto reply(
      GpuModelDto model,
      PublicContextDto conditions,
      PublicPriceLookupDto data,
      List<String> assumptions) {
    var items = data.items();
    var sources = new LinkedHashMap<String, Map<String, Object>>();
    var results =
        items.stream()
            .map(
                item -> {
                  var evidenceIds =
                      item.sources().stream()
                          .map(
                              source -> {
                                String id = source.get("evidenceId").toString();
                                sources.putIfAbsent(id, source);
                                return id;
                              })
                          .toList();
                  return new AgentResultDto(
                      item.offerId(),
                      item.observationId(),
                      item.normalizationId(),
                      item.rawPriceText(),
                      item.rawComponents(),
                      item.providerName(),
                      item.gpuCount(),
                      item.countryCode(),
                      item.regionCode(),
                      item.systemType(),
                      item.contractType(),
                      item.collectedAt(),
                      item.priceUsdGpuHour(),
                      "EFFECTIVE",
                      "OK",
                      evidenceIds);
                })
            .toList();
    var samples =
        new SampleCountsDto(
            items.stream().map(PublicPriceDto::providerId).distinct().count(),
            items.stream().map(PublicPriceDto::productId).distinct().count(),
            items.stream().map(PublicPriceDto::offerId).distinct().count(),
            items.stream().map(PublicPriceDto::observationId).distinct().count());
    String answer =
        items.isEmpty() ? "조회한 범위에서 출처가 확인된 온디맨드 확정가를 찾지 못했습니다." : describe(model, items);
    return new AgentMessageResponseDto(
        null,
        items.isEmpty() ? "NO_DATA" : "ANSWERED",
        answer,
        conditions,
        List.copyOf(assumptions),
        List.of(),
        List.of(),
        List.of(),
        results,
        null,
        null,
        null,
        List.copyOf(sources.values()),
        samples,
        "QUERIED",
        "OFFER_OBSERVATION",
        data.asOf(),
        DISCLAIMER);
  }

  private static String describe(GpuModelDto model, List<PublicPriceDto> items) {
    var text = new StringBuilder(model.name() + " 온디맨드 공개 관측 가격입니다. 리전과 상품 구성을 확인해 주세요.");
    for (var item : items) {
      text.append("\n- ")
          .append(item.providerName())
          .append(" · ")
          .append(Objects.toString(item.regionCode(), "리전 미확인"))
          .append(" · ")
          .append(Objects.toString(item.gpuCount(), "미확인"))
          .append(" GPU")
          .append(" · ")
          .append(item.priceUsdGpuHour())
          .append(" USD/GPU·시간")
          .append(" · 수집 ")
          .append(OBSERVED_AT.format(item.collectedAt()))
          .append(" · 출처 ")
          .append(item.sources().get(0).get("sourceName"));
    }
    return text.toString();
  }

  private static List<GpuModelDto> mentionedModels(String question, List<GpuModelDto> catalog) {
    if (catalog.isEmpty()) {
      return List.of();
    }
    var names =
        catalog.stream()
            .map(model -> model.name().toLowerCase(Locale.ROOT))
            .distinct()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .map(Pattern::quote)
            .toList();
    var matcher = bounded(String.join("|", names)).matcher(question);
    var matches = new HashSet<String>();
    while (matcher.find()) {
      matches.add(matcher.group());
    }
    return catalog.stream()
        .filter(model -> matches.contains(model.name().toLowerCase(Locale.ROOT)))
        .toList();
  }

  private static Pattern countryPattern(String code) {
    var locale = new Locale.Builder().setRegion(code).build();
    String names =
        Pattern.quote(locale.getDisplayCountry(Locale.ENGLISH))
            + "|"
            + Pattern.quote(locale.getDisplayCountry(Locale.KOREAN));
    var alias = COUNTRY_ALIASES.get(code);
    if (alias != null) {
      names += "|" + alias.pattern();
    }
    return Pattern.compile(
        "(?iu:" + bounded(names).pattern() + ")|(?i:\\b(?:in|for)\\s+(?:the\\s+)?)" + code + "\\b");
  }

  private static boolean mentions(String question, String name) {
    return bounded(Pattern.quote(name.toLowerCase(Locale.ROOT))).matcher(question).find();
  }

  private static Pattern bounded(String alternatives) {
    return Pattern.compile("(?<![a-z0-9])(?:" + alternatives + ")(?![a-z0-9])");
  }

  private static boolean conflict(String present, String requested) {
    return present != null && !present.equalsIgnoreCase(requested);
  }

  private static String upper(String value) {
    return value == null ? null : value.toUpperCase(Locale.ROOT);
  }

  public static class SensitiveInput extends RuntimeException {}
}
