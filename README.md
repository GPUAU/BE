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
- simulation: 사용자 입력 기반 원가·후보가격 계산
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

수집 배치와 Agent는 별도 서비스다. 원가·사업성·Agent API와 자동 수집은 후속 범위다. DB 접속 환경변수와 사전에 적재된 ERD 스키마가 필요하다.

## 접근과 비밀정보

`local` 프로필에서는 `127.0.0.1`에 바인딩하고 현재 구현한 카탈로그·시장가격·환율·수집 경고 GET 및 환율 시뮬레이션 POST를 허용한다. 나머지 경로·메서드는 거부한다. `deploy`, `prod` 또는 `production`이 함께 활성화되면 로컬 허용 정책은 적용되지 않는다. 로컬에서만 CSRF를 비활성화하며, 팀 인증·인가 방식은 아직 구현되지 않았다. 배포 환경에서 `local` 프로필이나 바인딩 주소 변경을 사용하지 않는다.

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
