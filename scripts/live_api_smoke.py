"""Read-only live integration checks against Spring or the frontend proxy.

Run with: python3 scripts/live_api_smoke.py
The exchange simulation is a non-persistent calculation.
"""
import json
import os
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

BASE = os.environ.get('GPU_API_BASE_URL', 'http://127.0.0.1:18082').rstrip('/')


def request(path, body=None):
    payload = None if body is None else json.dumps(body).encode()
    req = Request(BASE + path, data=payload, headers={'Content-Type': 'application/json'})
    try:
        with urlopen(req, timeout=90) as response:
            return response.status, json.load(response)
    except HTTPError as error:
        return error.code, json.load(error)


def check(condition, name):
    if not condition:
        raise AssertionError(name)
    print('PASS:', name)


status, catalog = request('/api/v1/catalog')
check(status == 200 and catalog['isSuccess'], '카탈로그 조회')
models = {m['name']: m['id'] for m in catalog['result']['models']}
check('H100' in models and 'B200' in models, 'H100·B200 모델 확인')
now = datetime.now(timezone.utc)
params = {'from': (now - timedelta(days=730)).isoformat(), 'to': now.isoformat()}
for name in ('H100', 'B200'):
    status, history = request('/api/v1/market/price-histories?' + urlencode({**params, 'modelId': models[name]}))
    check(status == 200 and history['isSuccess'] and history['result']['series'], name + ' 가격 이력')
    if name == 'H100':
        print('  오퍼:', len(history['result']['series']))
        check(len(history['result']['series']) > 100, 'H100 100개 초과 오퍼 조회')
    else:
        point = next(p for s in history['result']['series'] for p in s['points'] if p['normalizationId'] and p['effectiveUsdGpuHour'] is not None)

observation, normalization = point['observationId'], point['normalizationId']
status, detail = request('/api/v1/market/observations/' + observation + '?' + urlencode({'normalizationId': normalization}))
check(status == 200 and detail['isSuccess'] and str(detail['result']['observationId']) == observation, '관측 상세 조회')
check(isinstance(detail['result']['evidence'], list), '출처 목록 응답')
status, fx = request('/api/v1/exchange-rates/latest')
check(status == 200 and fx['isSuccess'] and fx['result']['status'] in ('AVAILABLE', 'NOT_AVAILABLE'), '최신 환율 조회')
payload = {'items': [{'observationId': observation, 'normalizationId': normalization}], 'priceType': 'EFFECTIVE', 'krwPerUsd': '1400'}
status, converted = request('/api/v1/exchange-rate-simulations', payload)
item = converted['result']['items'][0]
check(status == 200 and item['status'] == 'OK', '직접 환율 시뮬레이션')
expected = Decimal(str(point['effectiveUsdGpuHour'])) * Decimal('1400')
check(abs(Decimal(str(item['krwPerGpuHour'])) - expected) <= Decimal('0.000001'), '환산 금액 검증')
status, alerts = request('/api/v1/operations/alerts?limit=50')
check(status == 200 and alerts['isSuccess'] and isinstance(alerts['result']['items'], list), '운영 경고 목록')
status, invalid = request('/api/v1/market/price-histories?modelId=0&from=2026-01-01T00%3A00%3A00Z&to=2026-01-02T00%3A00%3A00Z')
check(status == 422 and not invalid['isSuccess'], '잘못된 모델 입력 거부')
status, missing = request('/api/v1/market/observations/999999999999')
check(status == 404 and not missing['isSuccess'], '없는 관측 조회 처리')

market = '/api/v1/market/'
scope = {'modelId': models['B300'], 'providerType': 'GENERAL_CLOUD',
         'contractType': 'ON_DEMAND', 'regionCode': 'us-west-2'}


def market_query(path, filters):
    return request(market + path + '?' + urlencode(filters, doseq=True))


status, latest = market_query('offers', scope)
check(status == 200 and latest['isSuccess'] and all(
    item['freshness']['status'] != 'STALE' for item in latest['result']['items']),
    '오퍼 목록 기본 조회와 오래된 가격 제외')

page_filters = {'modelId': models['B300'], 'includeStale': 'true', 'limit': 2}
status, offers = market_query('offers', page_filters)
first = offers['result']
check(status == 200 and offers['isSuccess'] and len(first['items']) == 2 and first['nextCursor'],
      '오퍼 첫 페이지와 다음 커서')
status, offers = market_query('offers', {**page_filters, 'cursor': first['nextCursor']})
second = offers['result']
check(status == 200 and offers['isSuccess'] and first['asOf'] == second['asOf']
      and {item['offerId'] for item in first['items']}.isdisjoint(
          item['offerId'] for item in second['items']), '다음 페이지 기준 시각 유지와 오퍼 중복 방지')
status, invalid = market_query('offers', {**page_filters, 'regionCode': 'us-west-2',
                                           'cursor': first['nextCursor']})
check(status == 422 and not invalid['isSuccess'], '필터가 달라진 커서 거부')

status, regional = market_query('offers', {**scope, 'includeStale': 'true'})
items = regional['result']['items']
check(status == 200 and regional['isSuccess'] and items and all(
    item['product']['regionCode'] == scope['regionCode']
    and item['providerType'] == scope['providerType']
    and item['contract']['type'] == scope['contractType']
    and item['modelId'] == scope['modelId'] for item in items), '모델·사업자 유형·계약·리전 필터')
offer_id = items[0]['offerId']
status, offer = market_query('offers/' + offer_id, {'currency': 'KRW'})
selected = offer['result']['offer']
obs = selected['lastSuccessfulObservation']
check(status == 200 and offer['isSuccess'] and selected['offerId'] == offer_id
      and obs['rawPriceText'] and obs['collectedAt'] and any(
          source.get('sourceUrl') for source in obs['evidence']), '목록에서 상세의 가격 원문과 출처로 이동')
won = obs['effective']['krw']
if fx['result']['status'] == 'AVAILABLE':
    expected_won = Decimal(obs['effective']['amount']) * Decimal(won['krwPerUsd'])
    check(won['status'] == 'OK' and won['referenceDate'] == fx['result']['referenceDate']
          and abs(Decimal(won['amount']) - expected_won) <= Decimal('0.000001'),
          '오퍼 상세 기준 환율과 원화 환산 검산')
else:
    check(won['status'] == 'UNAVAILABLE' and obs['effective']['amount'] is not None,
          '환율 미확보 시 USD 가격 유지')
status, missing = request(market + 'offers/999999999999')
check(status == 404 and not missing['isSuccess'], '없는 오퍼 조회 처리')

status, response = market_query('summaries', scope)
summary = response['result']
check(status == 200 and response['isSuccess'] and summary['rules']['freshnessHours'] == 48,
      '시장 요약과 48시간 정책')
eligible = sum(item['comparison']['eligibleForAggregate'] for item in items)
grouped = sum(group['sampleCounts']['offerCount'] for group in summary['groups'])
check(grouped == eligible and grouped + sum(summary['excludedSummary'].values()) == len(items)
      and all(group['groupKey']['regionCode'] == scope['regionCode']
              and Decimal(group['minimum']) <= Decimal(group['median']) <= Decimal(group['maximum'])
              for group in summary['groups']), '집계 표본·제외 건수 대조와 리전 구분')
status, response = market_query('summaries', {**scope, 'regionCode': 'e2e-missing-region'})
check(status == 200 and response['isSuccess'] and response['result']['groups'] == [],
      '가격 미확보 조건에서 빈 시장 요약')
status, invalid = market_query('summaries', {'comparisonType': 'EXCLUDING_REGION', **scope})
check(status == 422 and not invalid['isSuccess'], '지원하지 않는 리전 통합 집계 거부')
status, invalid = request('/api/v1/exchange-rate-simulations', {**payload, 'krwPerUsd': '0'})
check(status == 422 and not invalid['isSuccess'], '0원 환율 입력 거부')
