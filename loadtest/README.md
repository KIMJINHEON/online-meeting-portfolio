# HLS Load Test (Distributed)

이 폴더의 `hls-load.js`는 HLS 마스터 → variant → TS 순서로 요청을 생성합니다.
기본값은 실제 시청 패턴에 가깝게 조정되어 있습니다.
- VU별로 이미 받은 세그먼트는 다시 요청하지 않음
- 플레이리스트 `targetduration` 기반 자동 페이싱
- 고정 최고 화질 대신 ABR 유사 분산(`ABR_STRATEGY=adaptive`)
- 사용자 페이지 API 폴링 패턴(메타/투표/출석/자료/통계/채팅조회)도 함께 시뮬레이션

## 실 운영 유사 모드 (기본값)
기본값 `API_NO_DB_WRITE_MODE=0` 이며, 이 모드에서는:
- VU 시작 시 `POST /api/viewer/play` 1회 호출 (실제 시청자 시청기록 적재 동등)
- VU 종료 시 `POST /api/auth/disconnect?reason=browser_exit` 1회 호출 (브라우저 종료 동등)
- 보호 API는 `API_BEARER_TOKEN` 또는 `API_COOKIE`로 인증된 상태로 호출
- 첫 iteration에서 `INITIAL_BUFFER_SEGMENTS`(기본 4)개 세그먼트를 버스트로 받아 HLS.js의 초기 버퍼 채움 동작을 모사
- 기본 ramp-up 2초, jitter 1초로 라이브 시작 직후 동시 진입 트래픽 모사

`API_BEARER_TOKEN` 또는 `API_COOKIE`를 제공하지 않으면 보호 API가 401을 반환하며 fail로 집계되니 주의.
실제 부하 검증을 하려면 반드시 유효한 토큰 또는 세션 쿠키를 환경변수로 주입할 것.

## DB 비기록 안전 모드 (옵션)
`API_NO_DB_WRITE_MODE=1`로 켜면:
- `/api/viewer/play`, `/api/auth/disconnect` 같은 쓰기 API를 호출하지 않음
- 보호 API는 인증 없이 호출하여(주로 401) 애플리케이션 경로 부하만 확인
- DB에 시청기록 신규 적재를 유발하지 않도록 구성
- 401 응답은 `ok`로 집계됨

주의: 한 대에서 1000 VU를 돌리면 부하 발생기 자체가 먼저 병목이 생길 수 있으므로,
가능하면 여러 대로 분산 실행하세요.

## 빠른 실행 예시
```bash
HLS_URL="https://hls.example.com/live/stream/playlist.m3u8" \
VUS=250 DURATION_SEC=600 SEGMENTS_PER_ITER=1 \
node hls-load.js
```

## 분산 실행 예시 (4대)
각 서버에서 동일한 명령을 실행하되 `VUS=250`로 나눕니다.
```bash
HLS_URL="https://hls.example.com/live/stream/playlist.m3u8" \
VUS=250 DURATION_SEC=600 SEGMENTS_PER_ITER=1 \
node hls-load.js
```

## 주요 옵션
- `REALISTIC_MODE` (`1` 기본): `targetduration` 기반 자동 페이싱 사용
- `AUTO_POLL_FACTOR` (`0.75` 기본): 플레이리스트 주기 배수
- `ABR_STRATEGY` (`adaptive` 기본): `adaptive|highest|lowest|middle|random`
- `TEST_MODE`: `full|playlist_only`
- `RAMP_UP_SEC` (`2` 기본): VU 선형 분산 도착 시간 (라이브 시작 직후 동시 진입 모사)
- `START_JITTER_MS` (`1000` 기본): 진입 시점 랜덤 지터
- `INITIAL_BUFFER_SEGMENTS` (`4` 기본): 첫 iter에서 버스트로 받을 세그먼트 수 (HLS.js 초기 버퍼 모사)
- `SIMULATE_UI_APIS` (`1` 기본): 사용자 페이지 API 폴링 시뮬레이션 on/off
- `APP_BASE_URL` (`https://meeting.example.com` 기본): API 호출 베이스 URL
- `API_STREAM_KEY` (기본: `HLS_URL`에서 자동 추출)
- `API_NO_DB_WRITE_MODE` (`0` 기본): 1로 두면 viewer/play, auth/disconnect 호출 생략
- `API_BEARER_TOKEN` / `API_COOKIE`: 실모드에서 보호 API 인증용 (둘 중 하나 제공 권장)
- `API_INCLUDE_CHAT_POLL` (`1` 기본): `/api/chat/messages` 조회 폴링 포함
- `API_INCLUDE_AUTH_ME_ONCE` (`1` 기본): 시작 시 `/api/auth/me` 1회 호출
- `API_TIMEOUT_MS` (`10000` 기본): API 호출 타임아웃
- `HLS_USER_AGENT` / `HLS_REFERER` / `HLS_ORIGIN`: 기본값으로 Chrome UA + `meeting.example.com` Referer/Origin 자동 첨부

## 실 운영 유사 (인증된 시청자 500명 시뮬레이션)
```bash
HLS_URL="https://hls.example.com/live/stream/playlist.m3u8" \
APP_BASE_URL="https://meeting.example.com" \
API_STREAM_KEY="stream" \
API_BEARER_TOKEN="<유효한 JWT>" \
SIMULATE_UI_APIS=1 \
VUS=500 DURATION_SEC=120 \
node hls-load.js
```

## DB 비기록 안전 모드 예시 (인증 없이 경로 부하만 확인)
```bash
HLS_URL="https://hls.example.com/live/stream/playlist.m3u8" \
APP_BASE_URL="https://meeting.example.com" \
API_STREAM_KEY="stream" \
SIMULATE_UI_APIS=1 API_NO_DB_WRITE_MODE=1 \
VUS=500 DURATION_SEC=120 \
node hls-load.js
```

## 결과 해석 요약
- `segment.fail`이 0에 가까워야 안정
- `segment.avgMs`가 세그먼트 길이(예: 10s)보다 충분히 짧아야 안정
- `playlist.fail`가 급증하면 불안정
- `api.byEndpoint`에서 엔드포인트별 `avgMs`, `failReasons` 확인
