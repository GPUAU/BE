# GPU 시장가격 서비스 백엔드

GPU 시장가격 서비스의 Spring 백엔드다.

Java 17, Spring Boot 3.3.7, Gradle 8.12.1, PostgreSQL 16 이상을 사용한다.

<details>
<summary>Java 코드 스타일과 자동 정리 방법</summary>

이 프로젝트는 [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)를 기준으로 한다.

- 들여쓰기는 공백 2칸, 한 줄은 원칙적으로 100자 이내로 작성한다.
- `if`, 반복문 등에는 중괄호를 사용하고, 한 줄에 하나의 문장만 작성한다.
- 와일드카드 import를 쓰지 않고, 사용하지 않는 import를 정리한다.
- 클래스는 `UpperCamelCase`, 메서드·변수는 `lowerCamelCase`, 상수는 `UPPER_SNAKE_CASE`로 작성한다.
- Java 소스와 테스트 모두 Google Java Format 1.25.2를 사용한다. 기존 동작을 바꾸지 않는 서식 정리 도구다.

```sh
./gradlew googleJavaFormat       # 코드 정리
./gradlew googleJavaFormatCheck  # 변경 없이 서식·와일드카드 import 확인
./gradlew check                  # 서식 검사와 테스트
```

최초 실행 시 Maven Central에서 포맷 도구 의존성을 내려받는다. IntelliJ에서는 Google Java Format 플러그인을 사용하거나 위 Gradle 명령을 실행한다. 포맷 도구가 검사하지 않는 이름·설계·Javadoc 등의 규칙은 코드 리뷰에서 확인한다.

</details>

환율·대출 금리 API 연동을 위한 키 이름은 `.env.example`에 준비되어 있다. 로컬 `.env`에 `KOREAEXIM_EXCHANGE_API_KEY`, `KOREAEXIM_LOAN_RATE_API_KEY`를 입력한다. 별도 발급 여부와 대출 금리 API 제공 범위는 연동 시 확인한다. 키를 입력해도 수집 기능이 자동 구현되지는 않는다.

## 도메인 구조

각 패키지의 `package-info.java`에서 역할을 확인한다. 실제 기능 개발 시 각 도메인 안에 controller, service, repository, dto, entity를 필요한 범위에서 추가한다. 공통 응답·예외는 global에 둔다.

- catalog: 사업자·GPU·상품·계약 조건
- market: 가격 관측·비교·추이·원문 근거
- collection: 수집 상태와 운영 알림 조회 (수집 실행은 Python 배치)
- exchange: 자동 환율 조회 및 원화 환산
- simulation: 사용자 입력 기반 원가·후보가격과 현금흐름·IRR 계산
- agent: FastAPI Agent 연동 및 공개 조회 도구 경계
- global: 공통 응답·예외·설정

## 실행

JDK 17과 PostgreSQL이 필요하다. 활성 프로필을 지정하지 않으면 `application.yml`의 기본값에 따라 `local`로 실행한다. 배포 환경에서는 `SPRING_PROFILES_ACTIVE=deploy`를 명시한다.

로컬 DB는 `DATABASE_URL`(JDBC URL), `DATABASE_USER`, `DATABASE_PASSWORD`로 연결한다. `.env.example`에 변수 이름이 있으며, Spring은 `.env`를 자동으로 읽지 않으므로 실행 환경에 값을 주입해야 한다. `ddl-auto=validate`로 스키마를 검증하며, 실행 전에 ERD 스키마를 준비한다.

```sh
./gradlew test
./gradlew bootRun
```

### 현재 구현: 카탈로그 조회

`GET /api/v1/catalog`은 ERD의 `provider`, `gpu_model`, `product`에서 사업자·모델·세대·지역을 조회한다. 계약·상품·사업자 유형 등의 필터 선택지는 API 명세에 정의된 값이다. 데이터가 없으면 해당 목록은 빈 배열이며 미확인 지역과 출시일은 null이다. 응답은 `isSuccess`, `code`, `message`, `result`, `meta`를 사용하고 내부 DB 오류는 상세 정보를 노출하지 않는다.

`local` 프로필에서는 지정된 개발용 경로를 로컬 PC에서 호출할 수 있다. `deploy`에서는 인증·인가 정책이 구현될 때까지 모든 HTTP 요청을 차단한다. 로컬 개발 DB에서 AWS·Azure·Nebius·Verda 가격 관측을 검증했다.

### 현재 구현: 환율 조회와 가상 환산

- `GET /api/v1/exchange-rates/latest`: 저장된 한국수출입은행 USD/KRW 환율 중 한국 시간 기준 오늘 이전 가장 최근 값을 조회한다. 데이터가 없으면 `NOT_AVAILABLE`과 사유를 반환한다. 이전 공시일의 휴일 여부는 검증할 데이터가 없어 `isPreviousBusinessDay`는 null이다. 한국수출입은행 환율은 아래의 1회 수집 명령으로 적재할 수 있다.
- `POST /api/v1/exchange-rate-simulations`: 관측 ID와 환산 ID를 확인해 사용자가 입력한 환율로 일회성 계산한다. 해당 환산 종류가 없거나 ID가 일치하지 않으면 항목별 사유를 표시한다. 사용자 환율과 결과는 저장하지 않는다.

두 API는 `local` 프로필에서 호출할 수 있다. 요청과 응답의 상세 형식은 노션 API 명세를 참고한다. 환율의 원천 식별은 현재 출처 URL의 `koreaexim.go.kr`을 기준으로 하며, 수집기와 팀에서 고정 식별자를 합의하면 변경해야 한다.

수집 배치와 Agent는 별도 서비스다. IRR 계산 API와 자동 수집은 후속 작업이다. DB 접속 환경변수와 사전에 적재된 ERD 스키마가 필요하다.

## 접근과 비밀정보

`local` 프로필에서는 `127.0.0.1`에 바인딩하고 현재 구현한 카탈로그·시장가격·계약 비교·환율·수집 경고 GET 및 환율 시뮬레이션 POST를 허용한다. 나머지 경로·메서드는 거부한다. `deploy`, `prod` 또는 `production`이 함께 활성화되면 로컬 허용 정책은 적용되지 않는다. 로컬에서만 CSRF를 비활성화하며, 팀 인증·인가 방식은 아직 구현되지 않았다. 배포 환경에서 `local` 프로필이나 바인딩 주소 변경을 사용하지 않는다.

로컬 PostgreSQL 접속 환경변수를 지정한 뒤 다음과 같이 실행한다. 아래 접속 정보를 자신의 개발 DB에 맞게 설정한다.

```sh
export DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/gpu_market
export DATABASE_USER=your_local_db_user
export DATABASE_PASSWORD=
export SERVER_PORT=18082
./gradlew bootRun
```

테이블이 없는 새 DB에서는 요청이 보안 차단을 통과해도 `DATA_UNAVAILABLE`로 응답한다. 다른 포트의 Vue 개발 서버에서 브라우저로 직접 호출할 때는 별도의 CORS 정책이나 개발 프록시가 필요하다.

### Kubernetes 배포 설정

배포용 Spring 컨테이너의 환경변수에 `SPRING_PROFILES_ACTIVE=deploy`를 명시한다. 그러면 `application-deploy.yml`의 RDS 설정을 사용한다. 아래는 기존 Deployment의 `spec.template.spec.containers[].env`에 넣는 예시다. Secret 이름과 키는 배포 환경에서 사용하는 값에 맞춘다.

```yaml
env:
  - name: SPRING_PROFILES_ACTIVE
    value: deploy
  - name: RDS_JDBC_URL
    valueFrom:
      secretKeyRef:
        name: gpu-runtime
        key: datasource-url
  - name: RDS_USERNAME
    valueFrom:
      secretKeyRef:
        name: gpu-runtime
        key: datasource-username
  - name: RDS_PASSWORD
    valueFrom:
      secretKeyRef:
        name: gpu-runtime
        key: datasource-password
```

`deploy`에서는 Swagger가 비활성화되고, 배포용 접근 제어를 구현할 때까지 HTTP 요청을 차단한다. RDS 실연결, TLS, 네트워크 및 인증은 배포 환경에서 별도로 검증한다.

`GET /api/v1/operations/alerts`는 운영 경고를 필터와 커서로 조회한다. 빈 배열은 기록이 없다는 뜻이며 수집 성공을 보장하지 않는다. 커서는 `asOf`와 필터·정렬 위치를 유지하나, 그 시점보다 이전 시각으로 나중에 적재된 기록이나 상태 수정까지 완전히 고정하는 DB 스냅샷은 아니다.

실제 API 키·DB 비밀번호·내부 원가와 질문 원문을 코드, 로그, 테스트 예시, AI 도구 입력에 넣지 않는다. 외부 API 키는 서버 측 Secret으로 관리하고 클라이언트용 환경변수에 넣지 않는다. Kubernetes Secret 또는 승인된 Secret Manager의 접근 권한과 DB·서버 네트워크 접근을 최소화한다. GitHub 저장소의 공개 여부와 권한을 배포 전에 확인한다.

`.env`, 키 파일과 빌드 결과는 Git 및 Docker 빌드 컨텍스트에서 제외한다. 무시 규칙은 이미 추적된 파일이나 과거 커밋을 제거하지 않는다. 키가 이력에 포함된 경우 삭제만으로 안전해지지 않으므로 해당 키를 폐기·재발급하고 담당자에게 알린다. 브라우저 Network/Sources, 로그, 이미지와 저장소를 배포 전에 별도로 점검한다. 이 문서는 실제 클라우드 권한이나 기존 저장소 이력을 검증했다는 의미가 아니다.

## Swagger UI에서 API 테스트

### 오퍼와 시장 요약

| 메서드 | 경로 | 예시 |
|---|---|---|
| GET | `/api/v1/market/offers` | `?modelId=1&contractType=ON_DEMAND&regionCode=us-west-2&limit=25` |
| GET | `/api/v1/market/offers/{offerId}` | `/api/v1/market/offers/90?currency=KRW` |
| GET | `/api/v1/market/summaries` | `?modelId=1&providerType=GENERAL_CLOUD&contractType=ON_DEMAND` |

목록의 기본 페이지 크기는 50이며 최대 100이다. 다음 페이지는 같은 필터와 응답의 `nextCursor`로 조회한다. 기준 시각을 유지하며 신규 적재나 수정까지 고정하는 DB 스냅샷은 제공하지 않는다. 48시간이 지난 오퍼는 `includeStale=true`로 목록에서 확인할 수 있다.

요약에는 동일 리전·구성·계약에서 최근 48시간 내 정상 확인한 환산 가능 확정가를 집계한다. 한 사업자의 오퍼가 여러 개일 때는 해당 집단의 사업자별 최저가를 사용한다. 집계 제외 건수와 표본 수를 함께 제공한다. 최근 수집 결과와 가격 공개 여부를 판단한 점검은 별도로 반환한다. 미발견·가격 비공개는 완료한 점검의 검증된 공식 근거에 동일 출처·오퍼 ID와 조회 기준 시각을 포함하는 적용 기간이 있을 때만 집계 제외에 반영한다. 오퍼 ID는 상품·리전·계약 조건을 함께 식별한다. 현재 비교 유형은 `SAME_CONDITION`을 지원하며 기준 오퍼 비교와 리전 제외 집계는 후속 범위다.

집계 제외에 사용하는 공식 근거의 `source_evidence.excerpt` 예시다. 오퍼 ID는 문자열이며, 적용 기간은 UTC Unix 초로 기록한다. 시작은 포함하고 종료는 제외한다.

```json
{
  "offerAvailability": {
    "offerId": "123",
    "validFromEpoch": 1795046400,
    "validToEpoch": 1795132800
  }
}
```

기존에 적재된 과거 가격표의 미발견 기록에는 위 범위가 없어 최신 집계 제외에 사용하지 않는다. 새 수집기가 해당 근거를 저장하면 범위가 일치하는 오퍼부터 적용된다.


대시보드의 ‘리전별 시장 요약과 오퍼’는 이 세 API를 사용한다. 기존 사업자 카드의 참고 비교 데이터는 별도 스냅샷이다.

`local` 프로필로 서버를 실행한 뒤 `http://127.0.0.1:18082/swagger-ui/index.html`에 접속한다. 위 실행 예시에 `export SERVER_PORT=18082`를 추가하면 이 주소로 열린다. API 설명은 `http://127.0.0.1:18082/v3/api-docs`에서도 확인할 수 있다.

가격 이력은 **GET /api/v1/market/price-histories**를 열어 **Try it out**을 누르고 `modelId=2`, `from=2026-11-16T00:00:00Z`, `to=2026-11-18T00:00:00Z`를 입력한 뒤 **Execute**를 누른다. 이력의 `observationId`와 `normalizationId`를 **GET /api/v1/market/observations/{observationId}**에 입력하면 당시 원문과 출처를 확인할 수 있다. 데이터가 변경되면 예시 결과도 달라질 수 있다.

가격 이력은 modelId·기간 조건과 USD를 지원한다. 한 요청은 최대 731일, 12,000개 관측까지 허용한다. 관측 수가 상한을 넘으면 422 응답에 기간 또는 페이지 크기를 줄이라는 안내를 담는다. 오퍼별로 나눠 조회하려면 `limit`(1~100)을 보내고, 같은 모델·기간·limit으로 `nextCursor`를 `cursor`에 전달한다. 페이지는 오퍼 ID 순이며 응답 간 DB 스냅샷은 공유하지 않는다. Swagger UI와 API 문서는 local 프로필에서만 활성화하며 배포 프로필에서는 기본적으로 비활성화한다.

### 원가·후보 판매가격 계산

`POST /api/v1/cost-simulations`은 사용자가 입력한 가정으로 USD/GPU·시간 원가와 후보 판매가격을 계산한다. 매출 기준 이익률과 원가 가산율을 구분하며, 누락 원가·비용 상태·적용 금리와 환율·계산식 버전을 응답에 포함한다. 계산에는 `BigDecimal`과 DECIMAL128을 사용하고 응답에서 단가는 소수 12자리, 금액과 비율은 6자리에서 반올림한다. 입력과 결과는 저장하거나 외부 서비스로 보내지 않는다.

기본 요청은 일회성 취득비와 연간 운영비, 연 8,760시간 기준이다. 기존 원가 화면은 `assumptions.costProfile=SCREEN_SCENARIO`를 명시해 월 운영비, 별도 취득 항목, 말기 잔존가치, 달력 월, 별도 전기료를 사용할 수 있다. 달력 월은 자본회수분과 일반 운영비를 연 비용의 1/12로 배분하고, 별도 전기료는 해당 월의 실제 시간으로 계산한다. 전기료가 이미 운영비에 포함되면 중복 합산하지 않는다.

시장 비교가 선택된 요청은 `marketFilters`의 동일 리전·구성·계약 조건에 맞는 최신 공개 확정가를 출처·수집 시각·표본 수와 함께 반환한다. 자료가 없으면 `NOT_AVAILABLE`을 반환하며 원가와 후보가는 함께 제공한다. 비교를 선택하지 않으면 `NOT_REQUESTED`로 응답한다.

예시로 취득비 USD 400,000, 연 운영비 USD 34,000, GPU 8장, 5년, 가동률 70%, 목표 매출 이익률 20%, 금융 미반영을 넣으면 연 원가 USD 114,000, 유료 GPU 시간 49,056시간, 시간당 원가 USD 2.323874755382, 후보가 USD 2.904843444227이 나온다.

### IRR·목표 판매가격 계산

`POST /api/v1/irr-simulations`은 DR-17 초안의 연간 현금흐름을 계산한다. 0년 차에는 투자금을 차감하고, 매년 판매단가 × GPU 수 × 8,760시간 × 가동률에서 연간 운영비를 빼며, 마지막 해에 잔존가치를 더한다. 투자금과 분석 기간을 생략하면 각각 취득비와 상각 기간을 사용한다. 감가상각·대출 원리금·세금·운전자본은 현금흐름에서 제외한다. 입력과 계산 결과는 저장하거나 외부로 전송하지 않는다.

`irr-draft-1`은 분석 기간 1~100년, 후보가 최대 50개, 목표 IRR은 -1보다 큰 소수 비율(정수부 최대 4자리, 소수부 최대 8자리)을 허용한다. 후보가는 0 이상이며 순서와 중복을 보존한다. NPV가 0이 되는 IRR을 이분법으로 계산한다. 할인 인자 구간의 허용 오차는 10⁻²⁰, 최대 반복은 256회다. 목표 판매가는 목표 IRR에서 NPV가 0이 되는 단가를 직접 구하고, 0 이상에서 소수 12자리로 올림해 재평가한다. IRR은 소수 12자리로 표시하며 현금흐름은 최소 6자리로 표시하고 계산에 사용한 소수 자릿수를 보존한다. 운영 적용 전 팀이 계산 가정과 수치 기준을 검토한다.

`marketConstraint`가 `NONE`이면 시장 조회를 생략한다. `MINIMUM`, `MEDIAN`, `MAXIMUM`을 선택하면 `marketFilters`가 필요하다. 동일 리전·구성·계약의 단일 그룹에서 출처가 확인된 최신 확정가를 고르고, 사업자별 최저가를 대표값으로 사용한다. 응답에는 선택 기준가, 각 기준가의 IRR, 표본 수와 출처를 담는다. 비교 그룹이 여러 개이거나 가격을 확보하지 못하면 `MARKET_REFERENCE_UNAVAILABLE`, 목표가가 기준가를 넘으면 `NO_FEASIBLE_PRICE`로 반환한다. 후보가별 현금흐름은 두 경우 모두 제공한다.

예시로 취득비 USD 100,000, 연간 운영비 USD 31,400, GPU 1장, 가동률 100%, 분석 기간 1년, 잔존가치 USD 10,000을 입력하면 USD 15/GPU·시간의 1년 차 현금흐름은 USD 110,000, IRR은 10%다. 시장 제약 없이 목표 IRR을 10%로 설정하면 목표 판매가격도 USD 15/GPU·시간이 된다.

### 상품별 계약 비교

`GET /api/v1/market/products/{productId}/contracts?currency=USD`는 같은 상품 ID의 온디맨드·스팟·약정 오퍼를 선택 시각 기준으로 조회한다. `currency=KRW`이면 USD 금액과 저장된 환율의 원화 환산을 함께 제공한다. 모르는 상품 ID는 404이며, 존재하지만 계약 오퍼가 없는 상품은 빈 목록을 반환한다.

최근 48시간에 확인한 양수 온디맨드 확정가가 하나이고 비교 조건이 맞을 때 약정 할인율을 계산한다. 조건이 다른 환산 버전이나 최소 물량, 계약 기간·결제 옵션 미확인은 계산 불가 사유를 반환한다. 스팟은 조회 목록에 표시한다. 할인율만 공개된 경우 동일 상품의 기준 가격으로 계산한 값을 `SCENARIO`로 표시하고 기준 관측·환산 ID를 반환한다. 시나리오는 확정 할인율에 포함하지 않는다.

### 수집 실행 이력

`GET /api/v1/operations/collection-runs`는 실행 시작·종료 시각과 전체 상태, 대상별 결과 및 마지막 성공 시각을 반환한다. `from`(포함)과 `to`(제외)는 실행 시작 시각 기준이다. `status`는 `RUNNING`, `SUCCESS`, `PARTIAL_FAILURE`, `FAILED`를 지원한다. `sourceId`로 조회하면 해당 출처의 점검이 있는 실행과 그 출처의 대상만 반환한다.

- 한 실행의 대상별로 전체 범위 점검이 있으면 그중 최신 결과를 사용한다. 전체 범위 점검이 없으면 상품·오퍼 점검 중 최신 결과를 사용한다. `lastSuccessAt`은 해당 결과 시점까지 확인된 마지막 성공이다. 전체 범위 점검 결과를 표시할 때는 이전 전체 범위 점검의 성공 시각을 사용한다. 상품·오퍼 점검만 성공했다면 전체 범위 성공 시각은 갱신하지 않는다.
- 점검 기록이 없는 실행도 조회된다. 예정 대상 목록을 저장하는 테이블이 아직 없어 `NOT_RUN`을 추정하지 않으며, 확인 가능한 대상이 없으면 `targets: []`다.
- 최신 실행부터 정렬한다. `limit`은 기본 50, 최대 100이다. 같은 필터로 다음 커서를 사용하면 첫 요청의 기준 시각을 유지하며, 30분 후 만료된다. 각 요청은 별도 DB 스냅샷을 사용한다.
- 로컬 운영 경로에서 호출할 수 있다. 배포 프로필은 운영 접근 제어를 적용할 때까지 요청을 차단한다.

### 한국수출입은행 환율 1회 수집

`KOREAEXIM_EXCHANGE_API_KEY`, `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`를 실행 환경 또는 로컬 `.env`에 설정하고 아래 명령을 실행한다. 키는 서버에서만 사용하고 출처 URL에 저장하지 않는다. 이미 환경변수가 있으면 `.env`보다 우선한다.

```sh
python3 scripts/collect_exchange_rate.py
```

한국 시간의 오늘부터 최대 10일 전까지 공시된 USD 매매기준율을 찾고 PostgreSQL에 저장한다. 당일 데이터가 없으면 이전 날짜를 확인하며 실제 공시 기준일을 남긴다. 같은 출처·통화·기준일이 이미 저장되어 있으면 변경하지 않는다. 이 명령은 **수동 1회 실행**이며 Airflow 자동 수집은 아직 연결하지 않았다. 수집 실패 시 기존 저장값을 변경하지 않는다.


### 로컬 API 연동 테스트

로컬 Spring 서버를 시작하고 예제 가격 데이터가 적재된 PostgreSQL에 연결한 뒤 실행한다. H100·B200 이력과 B300의 AWS 오리건 온디맨드 오퍼가 있는 개발 데이터가 필요하다.

```sh
python3 scripts/live_api_smoke.py
# 프론트 프록시를 통과하는 경우
GPU_API_BASE_URL=http://127.0.0.1:18083 python3 scripts/live_api_smoke.py
```

### 가격·상태 변화 조회

`GET /api/v1/market/changes`는 `modelId`, `from`(포함), `to`(제외)를 받는다. 조회 기간은 최대 731일이다. `providerIds`, `countryCode`, `regionCode`, `contractType`으로 좁힐 수 있다. Swagger에서 필터를 확인할 수 있다.

- 동일 오퍼에서 확인한 확정가의 최초 관측, 원본 단위 변경, 동일 단위·환산 규칙에서의 가격 변경을 구분한다. 가격 비교에는 기간 시작 전 직전 확정 관측도 사용한다. 정정 기록이나 정정 대상 전후 가격은 변화 계산에서 제외한다.
- 공식 근거가 있는 동일 오퍼의 미공개 점검 이후 공개 관측이 확인되면 공개 전환을 반환한다. 이벤트 시각은 관측 시각이며 실제 변경일은 별도로 확인해야 한다.
- `limit` 기본값은 50, 최대값은 100이다. 후속 요청에 같은 필터와 `nextCursor`를 전달한다. 커서는 30분 동안 유효하며 첫 요청의 `asOf`를 이어받는다. 각 요청은 별도 DB 스냅샷을 사용한다.
- `includeKrwDecomposition=true`이면 전후 관측에 저장된 공식 USD/KRW 환산 근거가 하나씩 있을 때 가격 효과와 환율 효과를 계산한다. 근거가 부족하면 계산 불가 상태를 반환한다. 대표 오퍼 교체는 시점별 대표가 재현 정책이 확보된 이후에 지원한다.

### 모델·사업자별 가격 확보 상태 조회

`GET /api/v1/market/availability-records?modelId=2`는 수집 대상별 최근 점검 상태(`latestCheck`), 마지막 공식 공개 상태(`latestDisclosure`), 마지막 정상 가격 관측(`lastSuccessfulObservation`)을 반환한다. 상품·오퍼가 연결되지 않은 대상도 조회할 수 있다. 공식 근거가 있는 미공개는 `NOT_PUBLIC`이며, 미발견은 최근 점검에 `NOT_FOUND`로 표시한다. 점검 이력이 없으면 `UNKNOWN`으로 표시한다.

- 최근 점검이 실패해도 이전 정상 가격·수집 시각·증빙을 유지한다. `freshness`는 가격 관측에 연결된 정상 확인 시각을 기준으로 하며 48시간을 넘기면 `STALE`이다.
- `providerIds`, `countryCode`, `contractType`으로 좁힐 수 있다. 국가나 계약으로 좁혔을 때 범위를 확인할 수 없는 전체 대상 점검은 해당 국가·계약의 상태 판정에 사용하지 않는다.
- `limit` 기본값은 50, 최대값은 100이다. 다음 페이지 요청에는 같은 필터와 `nextCursor`를 사용한다. 커서는 첫 요청의 `asOf`를 유지하며 30분 후 만료된다. 후속 페이지도 기준 시각 이후에 확보한 근거는 포함하지 않으며, 나중에 확인한 공식 원천 검증은 그 이전 시점의 확인으로 표시하지 않는다. 각 요청은 별도 DB 스냅샷을 사용한다.

### 몬테카를로 가격 범위와 목표 IRR 기준 판매가

두 API는 Spring에서 Java로 계산한다. 계산 데이터는 버전이 지정된 B300 가상 가격 이력 18개월이다. 응답의 `metadata.isSample`은 `true`이며 실제 시장 예측이나 확정 판매가격으로 사용할 수 없다. 입력·결과는 DB에 저장하거나 외부 서비스로 보내지 않는다.

| 메서드 | 경로 | 요청 | 응답 `result` |
| --- | --- | --- | --- |
| POST | `/api/v1/monte-carlo-forecasts` | `simulation`, `analysisMonths` | `metadata`, `evidence`, 가격 `history`, 첫 12개월 `monthly` 가격 분위수 |
| POST | `/api/v1/monte-carlo-pricing-simulations` | `config`, `selection` | `metadata`, `post12Trend`, 분석 기간의 `monthly`, `pricing`의 확률별 필요 판매가·후보가별 IRR·민감도·비교 시나리오 |

가격 단위는 USD/GPU·시간이다. 월 기준시간은 730시간이며 IRR과 비용·가동률 비율은 소수로 입력한다. `simulation`은 두 API에서 같은 구조를 사용한다.

두 API의 `metadata.runAt`은 서버가 계산을 시작한 시각(UTC)이다. `metadata.configHash`는 검증한 요청 전체의 SHA-256 값이다. 예측에서는 시뮬레이션 설정과 분석 기간을, 판매가 계산에서는 원가·가동률·목표 IRR·후보가를 포함한 `config`와 시장 선택 조건 `selection`을 식별한다. JSON 필드 순서나 같은 숫자의 표기만 바꾸면 해시는 같다. 가격 이력 파일은 별도의 `historyHash`로 식별한다.

```json
{
  "simulation": {
    "nPaths": 20000,
    "seed": 20261118,
    "meanBlockLength": 3,
    "jumpProbPerMonth": 0.03,
    "jumpSizeTri": [-0.30, -0.15, -0.05]
  },
  "analysisMonths": 48
}
```

판매가 요청 예시는 다음과 같다. `selection`에는 `scenario` 또는 `marketPrice12m` 중 하나를 사용한다.

```json
{
  "config": {
    "simulation": {
      "nPaths": 20000,
      "seed": 20261118,
      "meanBlockLength": 3,
      "jumpProbPerMonth": 0.03,
      "jumpSizeTri": [-0.30, -0.15, -0.05]
    },
    "investment": {
      "gpus": 64,
      "months": 48,
      "capexPerGpu": 55000,
      "rampMonths": 3,
      "residualValuePct": 0.10,
      "pricingMode": "FOLLOW_MARKET"
    },
    "uncertain": {
      "utilization": [0.55, 0.75, 0.90],
      "capexOverrun": [0.00, 0.03, 0.12],
      "opexPerGpuMonth": [700, 900, 1200],
      "opexAnnualInflation": [0.01, 0.03, 0.06]
    },
    "targetIrrAnnual": 0.15,
    "candidatePrices": [5, 6, 7, 8]
  },
  "selection": {
    "scenario": "BASELINE",
    "post12Trend": "EASE"
  }
}
```

- `nPaths`는 1,000~50,000, 분석 기간은 12~60개월, 후보가는 1~8개를 허용한다. 삼각분포는 최소·최빈·최대 순으로 입력한다.
- 시장 시나리오는 `CONSERVATIVE`(P25), `BASELINE`(P50), `OPTIMISTIC`(P75)다. 선택 분위 ±10%p의 12개월 도달 가격에 해당하는 경로로 판매가를 계산한다. `post12Trend`는 `KEEP`, `EASE`, `FLAT`을 지원한다.
- 시장가를 직접 입력하면 `pricing.selection.marketPricePercentile`에 12개월 후 가격이 입력가 이하인 경로의 비율(0~1)을 반환한다. 예를 들어 하위 6%이면 실제 값은 `0.06`, 경로 선택에 사용하는 `centerQuantile`은 `0.10`이다. 시나리오를 선택하면 `marketPricePercentile`은 `null`이다.
- `pricingMode`의 `FIXED`는 기간 동안 판매가를 고정한다. `FOLLOW_MARKET`은 매월 기준 판매가에 시장 지수를 곱한다. 두 방식 모두 투자비는 최초 월, 잔존가치는 마지막 월에 반영한다.
- 목표 IRR 달성 비율은 선택한 경로 중 목표 수익률로 할인한 순현재가치가 0 이상인 비율이다. 이는 IRR 해의 유일성을 보장하지 않는다. 후보가별 IRR 분위수는 처음 최대 1,000개 경로에서 구한다. 현금흐름 부호가 두 번 이상 바뀌어 IRR이 여러 개일 가능성이 있거나 월 IRR 탐색 구간(−99%~100%)에서 값을 구하지 못한 표본은 `irrUndefinedCount`에 포함하고 분위수 계산에서 제외한다. 부호 변경이 여러 번이라고 반드시 IRR 해가 여러 개인 것은 아니지만, 임의의 해를 확정하지 않기 위한 보수적 처리다. 모두 해당하면 IRR 분위수는 `null`이다. 13개월 이후의 가격 경로는 선택한 추세 가정을 적용한다.
- 숫자 입력은 JSON 숫자, 선택값은 문서에 표시한 이름을 사용한다. 지원하지 않는 모델·데이터 설정 등 요청 DTO에 없는 필드도 요청 형식 오류로 처리한다.
- 요청 형식 오류는 `400`, 입력 제약 위반은 `422`, 같은 Spring 인스턴스에서 계산 중이면 `429 SIMULATION_BUSY`를 반환한다. 현재 `local` 프로필의 POST만 허용하며 `deploy`는 기존 배포 보안 정책을 적용한다.
- 같은 알고리즘 버전과 데이터 해시, 입력·시드를 사용하면 Java 결과를 재현할 수 있다. Python과의 회귀 비교는 계산기에 동일한 경로·투자비·가동률 표본을 넣어 검증한다.

```sh
./gradlew test --tests '*MonteCarlo*'
python3 scripts/generate_monte_carlo_parity_fixture.py /path/to/FE \
  | cmp - src/test/resources/montecarlo/python-parity.json
```

두 번째 명령은 시연용 Python 엔진의 FE 커밋 `bd73c5c`를 체크아웃한 경로로 실행한다. Spring API 실행에는 Python 설치가 필요하지 않다.

### Agent 공개 가격 질의

`POST /api/v1/agent-messages`에 아래와 같이 질문한다.

```json
{"message":"B300 온디맨드 GPU 1장당 시간 가격을 출처와 함께 알려줘"}
```

Spring이 카탈로그에서 모델을 찾고 공개 오퍼 조회를 사용해 최근 48시간 내 확인한 USD 확정 가격을 최대 5개 알려준다. 결과마다 리전·GPU 구성·관측 시각·관측 ID·출처를 제공한다. 공개 상태로 수집한 관측과 조회 기준 시각까지 확보한 공식 가격 출처 또는 원천을 확인한 집계 출처를 사용한다. 표본 수는 응답에 포함된 오퍼 기준이다.

`publicContext`로 모델·국가·상품 형태·GPU 수·사업자 유형을 지정할 수 있다. 화면 조건과 질문의 조건이 다르면 확인을 요청한다. 모델 미지정은 `NEEDS_CLARIFICATION`, 조건에 맞는 가격이 없으면 `NO_DATA`, DB 조회 실패는 503이다. 민감 질의는 전송이나 저장 없이 차단하며, 미지원 요청 필드는 400으로 응답한다.

현재 Spring에서 공개 온디맨드 가격을 조회한다. 첫 요청에 `sessionId`를 생략하면 서버가 임시 ID를 발급한다. 이후 요청에 받은 ID를 보내면 지난 공개 조회 조건을 이어서 사용한다. 예를 들어 “미국 온디맨드 가격”에 모델을 되물으면, 같은 ID로 “B300”만 답해도 미국 조건이 유지된다. “그중 GPU 특화 사업자만”처럼 사업자 유형을 바꿀 수 있다. 후속 질문에서 바꾼 조건은 `changedConditions`, 그대로 사용한 조건은 `retainedConditions`에 담는다.

`DELETE /api/v1/agent-sessions/{sessionId}`는 세션을 종료하고 본문 없이 204를 반환한다. 동일 ID로 다시 삭제하거나 유효한 형식의 미존재·만료 ID를 삭제해도 204다. 종료된 ID를 질의에 쓰면 404가 반환되며, 새 대화는 ID를 생략해 시작한다.

세션은 단일 Spring 인스턴스의 메모리에서 발급 후 30분 동안 유효하다. 만료된 항목은 새 세션 발급 또는 해당 ID 조회·삭제 시 메모리에서 제거한다. 세션은 최대 1,024개이며, 한도를 넘으면 가장 먼저 발급된 세션이 종료된다. 프로세스 재시작 시 세션이 초기화되고, 다중 인스턴스 간에는 세션이 공유되지 않는다. 질문 원문·답변·비공개 정보는 세션에 저장하지 않는다. FastAPI·OpenAI 호출과 추이·약정·미래 질의는 후속 범위다. 배포 접근 정책은 기존과 동일하다.

### 실제 관측 이력을 이용한 몬테카를로

API-18 `POST /api/v1/monte-carlo-forecasts`, API-19 `POST /api/v1/monte-carlo-pricing-simulations`의 기존 요청에 `historySource: {"modelId": 4, "offerId": 255}`를 추가하면 선택한 모델과 오퍼의 관측 이력으로 계산합니다. `historySource`를 생략하면 기존의 B300 가상 샘플을 사용하며 응답의 `metadata.isSample`로 구분합니다.

실제 이력은 현재 UTC 월을 제외한 최근 12개 완료 월의 마지막 관측값입니다. 각 월에 확정 가격, 단일 성공 환산 결과, 같은 환산 규칙 및 공식 가격 출처 또는 검증된 집계 출처가 있어야 하며 월별 공백을 채우지 않습니다. 가격 정정 기록은 새로운 시점의 가격으로 취급하지 않습니다. 요건 미충족 또는 월별 가격 변화가 없으면 422를 반환합니다. 검증되지 않은 자료는 자동 대체하지 않습니다.

응답 `observations`는 실제 선택한 월별 관측 ID, 수집 시각, 원천 ID, 근거 ID와 환산 규칙을 제공합니다. 예측은 마지막 완료 월의 관측가격에서 시작하므로 진행 중인 월의 최신 가격과 다를 수 있습니다. 결과는 시뮬레이션이며 실제 가격이나 수익을 보장하지 않습니다. 계산 결과는 DB에 저장하지 않아 ERD 변경이 없습니다.
