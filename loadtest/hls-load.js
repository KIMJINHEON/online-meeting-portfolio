#!/usr/bin/env node
'use strict';

const { setTimeout: sleep } = require('node:timers/promises');
const { performance } = require('node:perf_hooks');

const HLS_URL = process.env.HLS_URL || process.argv[2];
const VUS = Number(process.env.VUS || 50);
const DURATION_SEC = Number(process.env.DURATION_SEC || 120);
const SEGMENTS_PER_ITER = Number(process.env.SEGMENTS_PER_ITER || 1);
const SLEEP_MS = Number(process.env.SLEEP_MS || 1000);
const LOG_EVERY = Number(process.env.LOG_EVERY || 10);
const REQUEST_TIMEOUT_MS = Number(process.env.REQUEST_TIMEOUT_MS || 15000);
const CONNECT_TIMEOUT_MS = Number(process.env.CONNECT_TIMEOUT_MS || 30000);
const KEEPALIVE_TIMEOUT_MS = Number(process.env.KEEPALIVE_TIMEOUT_MS || 10000);
const KEEPALIVE_MAX_TIMEOUT_MS = Number(process.env.KEEPALIVE_MAX_TIMEOUT_MS || 60000);
const UNDICI_CONNECTIONS = Number(process.env.UNDICI_CONNECTIONS || 1024);
const UNDICI_PIPLINING = Number(process.env.UNDICI_PIPLINING || 1);
const RAMP_UP_SEC = Number(process.env.RAMP_UP_SEC || 2);
const START_JITTER_MS = Number(process.env.START_JITTER_MS || 1000);
const INITIAL_BUFFER_SEGMENTS = Number(process.env.INITIAL_BUFFER_SEGMENTS || 4);
const PLAYLIST_RETRY = Number(process.env.PLAYLIST_RETRY || 1);
const SEGMENT_RETRY = Number(process.env.SEGMENT_RETRY || 1);
const RETRY_BACKOFF_MS = Number(process.env.RETRY_BACKOFF_MS || 200);
const HLS_USER_AGENT = process.env.HLS_USER_AGENT
  || 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';
const HLS_REFERER = process.env.HLS_REFERER || 'https://meeting.example.com/';
const HLS_ORIGIN = process.env.HLS_ORIGIN || 'https://meeting.example.com';
const TEST_MODE = (process.env.TEST_MODE || 'full').toLowerCase();
const REALISTIC_MODE = parseBool(process.env.REALISTIC_MODE, true);
const AUTO_POLL_FACTOR = Number(process.env.AUTO_POLL_FACTOR || 0.75);
const MIN_PACE_MS = Number(process.env.MIN_PACE_MS || 800);
const MAX_PACE_MS = Number(process.env.MAX_PACE_MS || 15000);
const PACE_JITTER_RATIO = Number(process.env.PACE_JITTER_RATIO || 0.15);
const SEGMENT_LOOKBACK = Number(process.env.SEGMENT_LOOKBACK || 3);
const MAX_SEEN_SEGMENTS = Number(process.env.MAX_SEEN_SEGMENTS || 512);
const ABR_STRATEGY = (process.env.ABR_STRATEGY || 'adaptive').toLowerCase();
const ABR_UPGRADE_STREAK = Number(process.env.ABR_UPGRADE_STREAK || 8);
const APP_BASE_URL = (process.env.APP_BASE_URL || 'https://meeting.example.com').trim().replace(/\/+$/, '');
const API_STREAM_KEY = (process.env.API_STREAM_KEY || '').trim();
const SIMULATE_UI_APIS = parseBool(process.env.SIMULATE_UI_APIS, true);
const API_NO_DB_WRITE_MODE = parseBool(process.env.API_NO_DB_WRITE_MODE, false);
const API_POLL_TICK_MS = Number(process.env.API_POLL_TICK_MS || 500);
const API_TIMEOUT_MS = Number(process.env.API_TIMEOUT_MS || 10000);
const API_INCLUDE_CHAT_POLL = parseBool(process.env.API_INCLUDE_CHAT_POLL, true);
const API_INCLUDE_AUTH_ME_ONCE = parseBool(process.env.API_INCLUDE_AUTH_ME_ONCE, true);
const API_BEARER_TOKEN = (process.env.API_BEARER_TOKEN || '').trim();
const API_COOKIE = (process.env.API_COOKIE || '').trim();

function parseBool(value, defaultValue = false) {
  if (value === undefined || value === null || value === '') {
    return defaultValue;
  }
  const normalized = String(value).trim().toLowerCase();
  if (['1', 'true', 'yes', 'on', 'y'].includes(normalized)) {
    return true;
  }
  if (['0', 'false', 'no', 'off', 'n'].includes(normalized)) {
    return false;
  }
  return defaultValue;
}

function createStatBucket(withBytes = false) {
  return {
    ok: 0,
    fail: 0,
    msSum: 0,
    msMin: Infinity,
    msMax: 0,
    statusCodes: {},
    failReasons: {},
    errorCodes: {},
    ...(withBytes ? { bytes: 0 } : {})
  };
}

const COMMON_HEADERS = {};
if (HLS_USER_AGENT) COMMON_HEADERS['User-Agent'] = HLS_USER_AGENT;
if (HLS_REFERER) COMMON_HEADERS.Referer = HLS_REFERER;
if (HLS_ORIGIN) COMMON_HEADERS.Origin = HLS_ORIGIN;

try {
  const { Agent, setGlobalDispatcher } = require('undici');
  setGlobalDispatcher(new Agent({
    connect: { timeout: CONNECT_TIMEOUT_MS },
    keepAliveTimeout: KEEPALIVE_TIMEOUT_MS,
    keepAliveMaxTimeout: KEEPALIVE_MAX_TIMEOUT_MS,
    connections: UNDICI_CONNECTIONS,
    pipelining: UNDICI_PIPLINING
  }));
} catch (err) {
  // Keep script runnable even if undici import is unavailable.
}

function createCookieJar() {
  return { map: new Map(), header: '', sessionId: '' };
}

function applyCookies(headers, jar) {
  if (!jar || !jar.header) return headers;
  return { ...headers, Cookie: jar.header };
}

function updateCookiesFromResponse(res, jar) {
  if (!jar || !res?.headers) return;
  let setCookies = [];
  if (typeof res.headers.getSetCookie === 'function') {
    setCookies = res.headers.getSetCookie();
  } else {
    const single = res.headers.get('set-cookie');
    if (single) setCookies = [single];
  }
  if (!setCookies || setCookies.length === 0) return;
  for (const cookie of setCookies) {
    const pair = cookie.split(';')[0].trim();
    const eq = pair.indexOf('=');
    if (eq > 0) {
      const name = pair.slice(0, eq).trim();
      const value = pair.slice(eq + 1).trim();
      if (name) jar.map.set(name, value);
    }
  }
  jar.header = Array.from(jar.map.entries())
    .map(([k, v]) => `${k}=${v}`)
    .join('; ');
}

if (!HLS_URL) {
  console.error('Usage: HLS_URL="https://example.com/live/stream/playlist.m3u8" node hls-load.js');
  process.exit(1);
}

if (!['full', 'playlist_only'].includes(TEST_MODE)) {
  console.error(`Invalid TEST_MODE=${TEST_MODE}. Allowed: full, playlist_only`);
  process.exit(1);
}

if (!['adaptive', 'highest', 'lowest', 'middle', 'random'].includes(ABR_STRATEGY)) {
  console.error(`Invalid ABR_STRATEGY=${ABR_STRATEGY}. Allowed: adaptive, highest, lowest, middle, random`);
  process.exit(1);
}

const metrics = {
  playlist: createStatBucket(false),
  playlistMaster: createStatBucket(false),
  playlistMedia: createStatBucket(false),
  segment: createStatBucket(true),
  api: createStatBucket(false),
  apiByEndpoint: {},
  vuErrors: 0
};

function inc(map, key) {
  if (!key) return;
  map[key] = (map[key] || 0) + 1;
}

function normalizeErrorCode(err) {
  if (err?.name === 'AbortError') return 'AbortError';
  return String(
    err?.code ||
    err?.cause?.code ||
    err?.cause?.name ||
    err?.name ||
    'unknown'
  );
}

function record(stat, ms, ok, bytes = 0, statusCode = null, failReason = '', errorCode = '') {
  const bucket = metrics[stat];
  if (!bucket) return;
  if (ok) bucket.ok += 1;
  else bucket.fail += 1;
  if (statusCode !== null && statusCode !== undefined) {
    inc(bucket.statusCodes, String(statusCode));
  }
  if (!ok && failReason) {
    inc(bucket.failReasons, failReason);
  }
  if (!ok && errorCode) {
    inc(bucket.errorCodes, errorCode);
  }
  if (ms !== null && !Number.isNaN(ms)) {
    bucket.msSum += ms;
    bucket.msMin = Math.min(bucket.msMin, ms);
    bucket.msMax = Math.max(bucket.msMax, ms);
  }
  if (bytes) bucket.bytes += bytes;
}

function recordPlaylist(playlistKind, ms, ok, statusCode = null, failReason = '', errorCode = '') {
  record('playlist', ms, ok, 0, statusCode, failReason, errorCode);
  if (playlistKind === 'playlistMaster' || playlistKind === 'playlistMedia') {
    record(playlistKind, ms, ok, 0, statusCode, failReason, errorCode);
  }
}

function getApiEndpointBucket(endpointName) {
  if (!metrics.apiByEndpoint[endpointName]) {
    metrics.apiByEndpoint[endpointName] = createStatBucket(false);
  }
  return metrics.apiByEndpoint[endpointName];
}

function recordApi(endpointName, ms, ok, statusCode = null, failReason = '', errorCode = '') {
  record('api', ms, ok, 0, statusCode, failReason, errorCode);
  const endpointBucket = getApiEndpointBucket(endpointName);
  if (ok) endpointBucket.ok += 1;
  else endpointBucket.fail += 1;
  if (statusCode !== null && statusCode !== undefined) {
    inc(endpointBucket.statusCodes, String(statusCode));
  }
  if (!ok && failReason) {
    inc(endpointBucket.failReasons, failReason);
  }
  if (!ok && errorCode) {
    inc(endpointBucket.errorCodes, errorCode);
  }
  if (ms !== null && !Number.isNaN(ms)) {
    endpointBucket.msSum += ms;
    endpointBucket.msMin = Math.min(endpointBucket.msMin, ms);
    endpointBucket.msMax = Math.max(endpointBucket.msMax, ms);
  }
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function clampIndex(value, length) {
  if (!Number.isFinite(value)) return 0;
  if (length <= 0) return 0;
  return clamp(Math.floor(value), 0, length - 1);
}

function resolveUrl(baseUrl, path) {
  try {
    return new URL(path, baseUrl).toString();
  } catch (err) {
    return '';
  }
}

function parseMediaPlaylist(text, baseUrl) {
  const lines = text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
  let targetDurationSec = 0;
  let mediaSequence = null;
  const segments = [];
  for (const line of lines) {
    if (line.startsWith('#EXT-X-TARGETDURATION:')) {
      const value = Number(line.split(':')[1]);
      if (Number.isFinite(value) && value > 0) {
        targetDurationSec = value;
      }
      continue;
    }
    if (line.startsWith('#EXT-X-MEDIA-SEQUENCE:')) {
      const value = Number(line.split(':')[1]);
      if (Number.isFinite(value) && value >= 0) {
        mediaSequence = value;
      }
      continue;
    }
    if (line.startsWith('#')) continue;
    const url = resolveUrl(baseUrl, line);
    if (url) segments.push(url);
  }
  return { segments, targetDurationSec, mediaSequence };
}

function extractSessionId(text) {
  if (!text) return '';
  const match = text.match(/wowzasessionid=([0-9]+)/i);
  return match ? match[1] : '';
}

function appendSessionId(url, sessionId) {
  if (!sessionId || !url) return url;
  if (url.includes('wowzasessionid=')) return url;
  const joiner = url.includes('?') ? '&' : '?';
  return `${url}${joiner}wowzasessionid=${sessionId}`;
}

function detectStreamKeyFromHlsUrl(hlsUrl) {
  try {
    const parsed = new URL(hlsUrl);
    const parts = parsed.pathname.split('/').filter(Boolean);
    const appIdx = parts.findIndex((p) => p === 'live' || p === 'live2');
    if (appIdx >= 0 && appIdx + 1 < parts.length) {
      return parts[appIdx + 1];
    }
  } catch (err) {
    // ignore
  }
  return 'stream';
}

const RESOLVED_STREAM_KEY = API_STREAM_KEY || detectStreamKeyFromHlsUrl(HLS_URL);

function buildApiHeaders() {
  const headers = { ...COMMON_HEADERS };
  if (!API_NO_DB_WRITE_MODE) {
    if (API_BEARER_TOKEN) {
      headers.Authorization = API_BEARER_TOKEN.startsWith('Bearer ')
        ? API_BEARER_TOKEN
        : `Bearer ${API_BEARER_TOKEN}`;
    }
    if (API_COOKIE) {
      headers.Cookie = API_COOKIE;
    }
  }
  return headers;
}

function buildUiApiTasks(baseUrl, streamKey) {
  const key = encodeURIComponent(streamKey || 'stream');
  const tasks = [
    {
      name: 'streamMeta',
      intervalMs: 30000,
      url: `${baseUrl}/api/stream-meta?streamKey=${key}`
    },
    {
      name: 'voteStatus',
      intervalMs: 10000,
      url: `${baseUrl}/api/vote/status?streamKey=${key}`
    },
    {
      name: 'attendance',
      intervalMs: 30000,
      url: `${baseUrl}/api/attendance?streamKey=${key}`
    },
    {
      name: 'attendanceVisibility',
      intervalMs: 10000,
      url: `${baseUrl}/api/attendance-visibility/status?streamKey=${key}`
    },
    {
      name: 'materials',
      intervalMs: 15000,
      url: `${baseUrl}/api/materials?streamKey=${key}&limit=200`
    },
    {
      name: 'stats',
      intervalMs: 15000,
      url: `${baseUrl}/api/stats`
    }
  ];
  if (API_INCLUDE_CHAT_POLL) {
    tasks.push({
      name: 'chatMessages',
      intervalMs: 5000,
      url: `${baseUrl}/api/chat/messages?streamKey=${key}&afterId=0&limit=100`
    });
  }
  return tasks;
}

async function fetchApi(endpointName, url, options = {}) {
  const start = performance.now();
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), Math.max(500, API_TIMEOUT_MS));
  try {
    const res = await fetch(url, {
      method: options.method || 'GET',
      cache: 'no-store',
      headers: buildApiHeaders(),
      body: options.body,
      signal: controller.signal
    });
    await drainBody(res);
    const expectedUnauthorized = API_NO_DB_WRITE_MODE && res.status === 401;
    const ok = res.ok || expectedUnauthorized;
    recordApi(endpointName, performance.now() - start, ok, res.status, ok ? '' : `http_${res.status}`, '');
    return ok;
  } catch (err) {
    const reason = err?.name === 'AbortError' ? 'timeout' : 'network';
    recordApi(
      endpointName,
      performance.now() - start,
      false,
      null,
      reason,
      normalizeErrorCode(err)
    );
    return false;
  } finally {
    clearTimeout(timeout);
  }
}

async function callViewerPlay() {
  if (API_NO_DB_WRITE_MODE || !APP_BASE_URL) return;
  await fetchApi('viewerPlay', `${APP_BASE_URL}/api/viewer/play`, { method: 'POST' });
}

async function callAuthDisconnect() {
  if (API_NO_DB_WRITE_MODE || !APP_BASE_URL) return;
  await fetchApi(
    'authDisconnect',
    `${APP_BASE_URL}/api/auth/disconnect?reason=browser_exit`,
    { method: 'POST', body: new URLSearchParams({ ts: String(Date.now()) }) }
  );
}

async function runVuApiPoller(endAt, streamKey) {
  if (!SIMULATE_UI_APIS || !APP_BASE_URL) {
    return;
  }
  if (API_INCLUDE_AUTH_ME_ONCE) {
    await fetchApi('authMeOnce', `${APP_BASE_URL}/api/auth/me`);
  }

  const tasks = buildUiApiTasks(APP_BASE_URL, streamKey).map((task) => ({
    ...task,
    intervalMs: Math.max(1000, task.intervalMs),
    nextAtMs: Date.now() + Math.floor(Math.random() * Math.max(1, task.intervalMs))
  }));

  while (Date.now() < endAt) {
    const now = Date.now();
    for (const task of tasks) {
      if (now < task.nextAtMs) {
        continue;
      }
      await fetchApi(task.name, task.url);
      do {
        task.nextAtMs += task.intervalMs;
      } while (task.nextAtMs <= now);
    }
    const remaining = endAt - Date.now();
    if (remaining <= 0) {
      break;
    }
    const waitMs = Math.max(50, Math.min(Math.max(100, API_POLL_TICK_MS), remaining));
    await sleep(waitMs);
  }
}

async function drainBody(res) {
  let bytes = 0;
  if (!res.body) return bytes;
  for await (const chunk of res.body) {
    bytes += chunk.length || 0;
  }
  return bytes;
}

async function fetchText(url, jar, playlistKind) {
  const start = performance.now();
  let lastFailure = { statusCode: null, failReason: 'unknown', errorCode: 'unknown' };

  for (let attempt = 0; attempt <= PLAYLIST_RETRY; attempt += 1) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
      const target = appendSessionId(url, jar?.sessionId);
      const res = await fetch(target, {
        cache: 'no-store',
        headers: applyCookies(COMMON_HEADERS, jar),
        signal: controller.signal
      });
      updateCookiesFromResponse(res, jar);
      const text = await res.text();
      const sessionId = extractSessionId(text);
      if (sessionId && jar) jar.sessionId = sessionId;

      if (res.ok) {
        recordPlaylist(playlistKind, performance.now() - start, true, res.status, '', '');
        return text;
      }

      lastFailure = { statusCode: res.status, failReason: `http_${res.status}`, errorCode: '' };
      const retryableHttp = res.status === 429 || (res.status >= 500 && res.status < 600);
      if (!(attempt < PLAYLIST_RETRY && retryableHttp)) break;
      if (RETRY_BACKOFF_MS > 0) await sleep(RETRY_BACKOFF_MS * (attempt + 1));
    } catch (err) {
      const reason = err?.name === 'AbortError' ? 'timeout' : 'network';
      lastFailure = { statusCode: null, failReason: reason, errorCode: normalizeErrorCode(err) };
      if (attempt < PLAYLIST_RETRY) {
        if (RETRY_BACKOFF_MS > 0) await sleep(RETRY_BACKOFF_MS * (attempt + 1));
      } else {
        break;
      }
    } finally {
      clearTimeout(timeout);
    }
  }

  recordPlaylist(
    playlistKind,
    performance.now() - start,
    false,
    lastFailure.statusCode,
    lastFailure.failReason,
    lastFailure.errorCode
  );
  return '';
}

function parseMasterVariants(text, baseUrl) {
  const lines = text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
  const variants = [];
  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];
    if (line.startsWith('#EXT-X-STREAM-INF')) {
      const next = lines[i + 1];
      if (next && !next.startsWith('#')) {
        const match = line.match(/BANDWIDTH=(\d+)/i);
        const bandwidth = match ? Number(match[1]) : 0;
        const url = resolveUrl(baseUrl, next);
        if (url) {
          variants.push({ url, bandwidth });
        }
      }
    }
  }
  variants.sort((a, b) => a.bandwidth - b.bandwidth);
  return variants;
}

function chooseInitialAdaptiveVariantIndex(length) {
  if (length <= 1) return 0;
  const r = Math.random();
  if (r < 0.15) return 0; // low-tier viewers
  if (r < 0.55) return clampIndex(Math.floor((length - 1) * 0.5), length); // mid-tier majority
  if (r < 0.85) return clampIndex(length - 2, length); // high-tier
  return length - 1; // very high-tier
}

function chooseVariantIndex(variants, vuState) {
  const total = variants.length;
  if (total <= 1) return 0;

  switch (ABR_STRATEGY) {
    case 'highest':
      return total - 1;
    case 'lowest':
      return 0;
    case 'middle':
      return clampIndex(Math.floor((total - 1) * 0.5), total);
    case 'random':
      return Math.floor(Math.random() * total);
    case 'adaptive':
    default:
      if (!Number.isFinite(vuState.variantIndex)) {
        vuState.variantIndex = chooseInitialAdaptiveVariantIndex(total);
      }
      vuState.variantIndex = clampIndex(vuState.variantIndex, total);
      return vuState.variantIndex;
  }
}

function adaptVariantAfterFetch(vuState, variantCount, { segOk, segFail }) {
  if (ABR_STRATEGY !== 'adaptive' || variantCount <= 1) {
    return;
  }
  if (segFail > 0) {
    vuState.variantIndex = clampIndex(vuState.variantIndex - 1, variantCount);
    vuState.successStreak = 0;
    return;
  }
  if (segOk <= 0) {
    return;
  }
  vuState.successStreak += segOk;
  if (vuState.successStreak >= ABR_UPGRADE_STREAK) {
    vuState.variantIndex = clampIndex(vuState.variantIndex + 1, variantCount);
    vuState.successStreak = 0;
  }
}

function rememberSeenSegment(vuState, url) {
  if (!url) return;
  if (vuState.seenSet.has(url)) return;
  vuState.seenSet.add(url);
  vuState.seenQueue.push(url);
  while (vuState.seenQueue.length > MAX_SEEN_SEGMENTS) {
    const dropped = vuState.seenQueue.shift();
    if (dropped) {
      vuState.seenSet.delete(dropped);
    }
  }
}

function pickUnseenTailSegments(vuState, segments, budgetOverride) {
  const segmentBudget = Math.max(1, budgetOverride || SEGMENTS_PER_ITER);
  const lookbackCount = Math.max(SEGMENT_LOOKBACK, segmentBudget);
  const recent = segments.slice(-lookbackCount);
  const unseen = recent.filter((url) => !vuState.seenSet.has(url));
  return unseen.slice(-segmentBudget);
}

function computeLoopDelayMs(targetDurationSec) {
  if (!REALISTIC_MODE) {
    return Math.max(0, SLEEP_MS);
  }
  const factor = Number.isFinite(AUTO_POLL_FACTOR) && AUTO_POLL_FACTOR > 0 ? AUTO_POLL_FACTOR : 1;
  const baseFromPlaylist = Number.isFinite(targetDurationSec) && targetDurationSec > 0
    ? targetDurationSec * 1000 * factor
    : 0;
  const baseline = baseFromPlaylist > 0 ? baseFromPlaylist : Math.max(1000, SLEEP_MS);
  const clamped = clamp(baseline, Math.max(100, MIN_PACE_MS), Math.max(MIN_PACE_MS, MAX_PACE_MS));
  const jitterRatio = clamp(PACE_JITTER_RATIO, 0, 0.5);
  const jitter = clamped * jitterRatio * (Math.random() * 2 - 1);
  return Math.max(100, Math.round(clamped + jitter));
}

async function fetchSegmentsFromPlaylist(url, jar, vuState) {
  const text = await fetchText(url, jar, 'playlistMaster');
  if (!text) {
    return { segments: [], targetDurationSec: vuState.lastTargetDurationSec || 0, variantCount: 0 };
  }

  const variants = parseMasterVariants(text, url);
  if (variants.length > 0) {
    const selectedIndex = chooseVariantIndex(variants, vuState);
    const pick = variants[clampIndex(selectedIndex, variants.length)];
    const variantText = await fetchText(appendSessionId(pick.url, jar?.sessionId), jar, 'playlistMedia');
    if (!variantText) {
      return { segments: [], targetDurationSec: vuState.lastTargetDurationSec || 0, variantCount: variants.length };
    }
    const parsed = parseMediaPlaylist(variantText, pick.url);
    if (Number.isFinite(parsed.targetDurationSec) && parsed.targetDurationSec > 0) {
      vuState.lastTargetDurationSec = parsed.targetDurationSec;
    }
    return {
      segments: parsed.segments.map((u) => appendSessionId(u, jar?.sessionId)),
      targetDurationSec: vuState.lastTargetDurationSec || 0,
      variantCount: variants.length
    };
  }

  const parsed = parseMediaPlaylist(text, url);
  if (Number.isFinite(parsed.targetDurationSec) && parsed.targetDurationSec > 0) {
    vuState.lastTargetDurationSec = parsed.targetDurationSec;
  }
  return {
    segments: parsed.segments.map((u) => appendSessionId(u, jar?.sessionId)),
    targetDurationSec: vuState.lastTargetDurationSec || 0,
    variantCount: 0
  };
}

async function fetchSegment(url, jar) {
  const start = performance.now();
  let lastFailure = { statusCode: null, failReason: 'unknown', errorCode: 'unknown' };

  for (let attempt = 0; attempt <= SEGMENT_RETRY; attempt += 1) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
      const res = await fetch(url, {
        cache: 'no-store',
        headers: applyCookies(COMMON_HEADERS, jar),
        signal: controller.signal
      });
      updateCookiesFromResponse(res, jar);
      const bytes = await drainBody(res);

      if (res.ok) {
        record('segment', performance.now() - start, true, bytes, res.status, '', '');
        return true;
      }

      lastFailure = { statusCode: res.status, failReason: `http_${res.status}`, errorCode: '' };
      const retryableHttp = res.status === 429 || (res.status >= 500 && res.status < 600);
      if (!(attempt < SEGMENT_RETRY && retryableHttp)) break;
      if (RETRY_BACKOFF_MS > 0) await sleep(RETRY_BACKOFF_MS * (attempt + 1));
    } catch (err) {
      const reason = err?.name === 'AbortError' ? 'timeout' : 'network';
      lastFailure = { statusCode: null, failReason: reason, errorCode: normalizeErrorCode(err) };
      if (attempt < SEGMENT_RETRY) {
        if (RETRY_BACKOFF_MS > 0) await sleep(RETRY_BACKOFF_MS * (attempt + 1));
      } else {
        break;
      }
    } finally {
      clearTimeout(timeout);
    }
  }

  record(
    'segment',
    performance.now() - start,
    false,
    0,
    lastFailure.statusCode,
    lastFailure.failReason,
    lastFailure.errorCode
  );
  return false;
}

function chooseStartupDelayMs(vuId, totalVus) {
  const rampMs = Math.max(0, RAMP_UP_SEC * 1000);
  const jitterMs = Math.max(0, START_JITTER_MS);
  if (rampMs === 0 && jitterMs === 0) return 0;
  const base = totalVus <= 1 ? 0 : Math.floor(((vuId - 1) * rampMs) / (totalVus - 1));
  const jitter = jitterMs > 0 ? Math.floor(Math.random() * jitterMs) : 0;
  return base + jitter;
}

async function runVu(id, endAt) {
  let iter = 0;
  const jar = createCookieJar();
  const apiPoller = runVuApiPoller(endAt, RESOLVED_STREAM_KEY)
    .catch(() => { metrics.vuErrors += 1; });
  const vuState = {
    seenSet: new Set(),
    seenQueue: [],
    variantIndex: Number.NaN,
    successStreak: 0,
    lastTargetDurationSec: 0
  };
  const startupDelayMs = chooseStartupDelayMs(id, VUS);
  if (startupDelayMs > 0) {
    await sleep(startupDelayMs);
  }
  await callViewerPlay();
  while (Date.now() < endAt) {
    const iterStartedAt = performance.now();
    iter += 1;
    const playlistResult = await fetchSegmentsFromPlaylist(HLS_URL, jar, vuState);
    let segOk = 0;
    let segFail = 0;
    if (TEST_MODE !== 'playlist_only' && playlistResult.segments.length > 0) {
      const isFirstIter = iter === 1;
      const segmentBudget = isFirstIter
        ? Math.max(1, INITIAL_BUFFER_SEGMENTS)
        : SEGMENTS_PER_ITER;
      const toFetch = pickUnseenTailSegments(vuState, playlistResult.segments, segmentBudget);
      for (const seg of toFetch) {
        const ok = await fetchSegment(seg, jar);
        rememberSeenSegment(vuState, seg);
        if (ok) segOk += 1;
        else segFail += 1;
      }
      adaptVariantAfterFetch(vuState, playlistResult.variantCount, { segOk, segFail });
    }
    if (LOG_EVERY && iter % LOG_EVERY === 0 && id === 1) {
      const done = Math.max(0, Math.min(100, Math.round(((Date.now() - (endAt - DURATION_SEC * 1000)) / (DURATION_SEC * 1000)) * 100)));
      console.log(
        `[progress] ${done}% VUs=${VUS} ` +
        `playlist(ok/fail)=${metrics.playlist.ok}/${metrics.playlist.fail} ` +
        `master(ok/fail)=${metrics.playlistMaster.ok}/${metrics.playlistMaster.fail} ` +
        `media(ok/fail)=${metrics.playlistMedia.ok}/${metrics.playlistMedia.fail} ` +
        `segment(ok/fail)=${metrics.segment.ok}/${metrics.segment.fail}`
      );
    }
    const desiredDelayMs = computeLoopDelayMs(playlistResult.targetDurationSec);
    const elapsedMs = performance.now() - iterStartedAt;
    const waitMs = Math.max(0, desiredDelayMs - elapsedMs);
    if (waitMs > 0) {
      await sleep(waitMs);
    }
  }
  await apiPoller;
  await callAuthDisconnect();
}

async function main() {
  const endAt = Date.now() + DURATION_SEC * 1000;
  const vus = Math.max(1, VUS);
  console.log(
    `[start] url=${HLS_URL} vus=${vus} duration=${DURATION_SEC}s segmentsPerIter=${SEGMENTS_PER_ITER}` +
    ` initialBufferSegments=${INITIAL_BUFFER_SEGMENTS}` +
    ` sleepMs=${SLEEP_MS} timeoutMs=${REQUEST_TIMEOUT_MS} connectTimeoutMs=${CONNECT_TIMEOUT_MS}` +
    ` rampUpSec=${RAMP_UP_SEC} startJitterMs=${START_JITTER_MS}` +
    ` playlistRetry=${PLAYLIST_RETRY} segmentRetry=${SEGMENT_RETRY}` +
    ` testMode=${TEST_MODE} realisticMode=${REALISTIC_MODE} abr=${ABR_STRATEGY}` +
    ` autoPollFactor=${AUTO_POLL_FACTOR} paceRangeMs=${MIN_PACE_MS}-${MAX_PACE_MS}` +
    ` simulateUiApis=${SIMULATE_UI_APIS} appBaseUrl=${APP_BASE_URL || '-'} streamKey=${RESOLVED_STREAM_KEY}` +
    ` apiNoDbWriteMode=${API_NO_DB_WRITE_MODE}`
  );
  if (!API_NO_DB_WRITE_MODE && !API_BEARER_TOKEN && !API_COOKIE) {
    console.warn('[warn] write mode is ON but no API_BEARER_TOKEN/API_COOKIE provided — protected APIs will return 401 and be counted as failures.');
  }
  const runners = [];
  for (let i = 0; i < vus; i += 1) {
    runners.push(runVu(i + 1, endAt).catch(() => { metrics.vuErrors += 1; }));
  }
  await Promise.all(runners);
  const playlistAvg = metrics.playlist.ok + metrics.playlist.fail > 0 ? metrics.playlist.msSum / (metrics.playlist.ok + metrics.playlist.fail) : 0;
  const segmentAvg = metrics.segment.ok + metrics.segment.fail > 0 ? metrics.segment.msSum / (metrics.segment.ok + metrics.segment.fail) : 0;
  const apiAvg = metrics.api.ok + metrics.api.fail > 0 ? metrics.api.msSum / (metrics.api.ok + metrics.api.fail) : 0;
  console.log('[summary]');
  console.log({
    playlist: {
      ok: metrics.playlist.ok,
      fail: metrics.playlist.fail,
      avgMs: Math.round(playlistAvg),
      minMs: Number.isFinite(metrics.playlist.msMin) ? Math.round(metrics.playlist.msMin) : 0,
      maxMs: Math.round(metrics.playlist.msMax),
      statusCodes: metrics.playlist.statusCodes,
      failReasons: metrics.playlist.failReasons,
      errorCodes: metrics.playlist.errorCodes
    },
    playlistMaster: {
      ok: metrics.playlistMaster.ok,
      fail: metrics.playlistMaster.fail,
      statusCodes: metrics.playlistMaster.statusCodes,
      failReasons: metrics.playlistMaster.failReasons,
      errorCodes: metrics.playlistMaster.errorCodes
    },
    playlistMedia: {
      ok: metrics.playlistMedia.ok,
      fail: metrics.playlistMedia.fail,
      statusCodes: metrics.playlistMedia.statusCodes,
      failReasons: metrics.playlistMedia.failReasons,
      errorCodes: metrics.playlistMedia.errorCodes
    },
    segment: {
      ok: metrics.segment.ok,
      fail: metrics.segment.fail,
      avgMs: Math.round(segmentAvg),
      minMs: Number.isFinite(metrics.segment.msMin) ? Math.round(metrics.segment.msMin) : 0,
      maxMs: Math.round(metrics.segment.msMax),
      bytes: metrics.segment.bytes,
      statusCodes: metrics.segment.statusCodes,
      failReasons: metrics.segment.failReasons,
      errorCodes: metrics.segment.errorCodes
    },
    api: {
      ok: metrics.api.ok,
      fail: metrics.api.fail,
      avgMs: Math.round(apiAvg),
      minMs: Number.isFinite(metrics.api.msMin) ? Math.round(metrics.api.msMin) : 0,
      maxMs: Math.round(metrics.api.msMax),
      statusCodes: metrics.api.statusCodes,
      failReasons: metrics.api.failReasons,
      errorCodes: metrics.api.errorCodes,
      byEndpoint: Object.fromEntries(
        Object.entries(metrics.apiByEndpoint).map(([name, bucket]) => {
          const count = bucket.ok + bucket.fail;
          const avg = count > 0 ? Math.round(bucket.msSum / count) : 0;
          return [name, {
            ok: bucket.ok,
            fail: bucket.fail,
            avgMs: avg,
            minMs: Number.isFinite(bucket.msMin) ? Math.round(bucket.msMin) : 0,
            maxMs: Math.round(bucket.msMax),
            statusCodes: bucket.statusCodes,
            failReasons: bucket.failReasons,
            errorCodes: bucket.errorCodes
          }];
        })
      )
    },
    vuErrors: metrics.vuErrors
  });
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
