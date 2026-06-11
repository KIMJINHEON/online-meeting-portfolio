import { memo, useEffect, useMemo, useRef, useState } from 'react';
import ViewerMeetingSelector from './ViewerMeetingSelector.jsx';

const emptyConfig = {
  baseUrl: '',
  defaultStreamKey: 'stream',
  pathTemplate: '/live/{streamKey}/playlist.m3u8',
  playbackQuery: '',
  defaultUrl: ''
};
const PDF_WORKER_SRC = '/pdf.worker.js';
const VIEWER_DEVICE_ID_STORAGE_KEY = 'viewerDeviceId';
let pdfJsLibPromise = null;
let hlsLibPromise = null;
const emptyAttendanceSummary = {
  online: 0,
  paper: 0,
  mail: 0,
  onsite: 0,
  fieldOnsite: 0,
  electronic: 0,
  paperSubmitted: 0,
  mailSubmitted: 0,
  onsiteSubmitted: 0,
  fieldOnsiteSubmitted: 0,
  electronicSubmitted: 0,
  attendingTotal: 0,
  total: 0
};


const trimUrl = (value) => (value || '').trim();
const resolveStreamUrl = (config, overrideStreamKey) => {
  const baseUrl = trimUrl(config?.baseUrl);
  const effectiveKey = trimUrl(overrideStreamKey) || trimUrl(config?.defaultStreamKey);
  if (baseUrl && effectiveKey) {
    const pathTemplate = trimUrl(config?.pathTemplate) || '/live/{streamKey}/playlist.m3u8';
    const resolvedPath = pathTemplate.replace('{streamKey}', effectiveKey);
    const base = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
    const path = resolvedPath.startsWith('/') ? resolvedPath : `/${resolvedPath}`;
    let joined = `${base}${path}`;
    const query = trimUrl(config?.playbackQuery);
    if (query) {
      joined += query.startsWith('?') ? query : `?${query}`;
    }
    return joined;
  }
  const explicit = trimUrl(config?.defaultUrl);
  return explicit;
};

const deriveOriginFallbackUrl = (value) => {
  const raw = trimUrl(value);
  if (!raw) {
    return '';
  }
  try {
    const parsed = new URL(raw);
    if (!parsed.hostname.endsWith('.example.com')) {
      return '';
    }
    const next = new URL(parsed.toString());
    next.hostname = 'hls.example.com';
    return next.toString();
  } catch (err) {
    return '';
  }
};

const safeExternalUrl = (value, { allowBlob = false } = {}) => {
  if (!value) {
    return '';
  }
  try {
    const base = typeof window === 'undefined' ? 'https://localhost' : window.location.origin;
    const url = new URL(value, base);
    if (url.protocol === 'http:' || url.protocol === 'https:') {
      return url.toString();
    }
    if (allowBlob && url.protocol === 'blob:') {
      return value;
    }
  } catch (err) {
    return '';
  }
  return '';
};

const isIosDevice = () => {
  if (typeof navigator === 'undefined') {
    return false;
  }
  const ua = navigator.userAgent || '';
  if (/iPhone|iPad|iPod/i.test(ua)) {
    return true;
  }
  // iPadOS desktop UA fallback
  return navigator.platform === 'MacIntel' && Number(navigator.maxTouchPoints) > 1;
};

const isLikelyInAppBrowser = () => {
  if (typeof navigator === 'undefined') {
    return false;
  }
  const ua = navigator.userAgent || '';
  return /(KAKAOTALK|NAVER|Line|Instagram|FBAN|FBAV|FB_IAB|Messenger|Telegram|wv)/i.test(ua);
};

const getAuthHintFromStorage = () => {
  if (typeof window === 'undefined') {
    return false;
  }
  try {
    return window.sessionStorage.getItem('viewerAuthedHint') === '1';
  } catch (err) {
    return false;
  }
};

const getOrCreateViewerDeviceId = () => {
  if (typeof window === 'undefined') {
    return '';
  }
  try {
    const existing = (window.localStorage.getItem(VIEWER_DEVICE_ID_STORAGE_KEY) || '').trim();
    if (existing) {
      return existing;
    }
    let created = '';
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      created = crypto.randomUUID();
    } else {
      created = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
    }
    window.localStorage.setItem(VIEWER_DEVICE_ID_STORAGE_KEY, created);
    return created;
  } catch (err) {
    return '';
  }
};

const loadHlsLib = async () => {
  if (!hlsLibPromise) {
    hlsLibPromise = import('hls.js')
      .then((mod) => mod?.default || mod)
      .catch((err) => {
        hlsLibPromise = null;
        throw err;
      });
  }
  return hlsLibPromise;
};

const buildPdfIframeUrl = (value, page = 1) => {
  const safe = safeExternalUrl(value);
  if (!safe) {
    return '';
  }
  try {
    const url = new URL(safe);
    const safePage = Math.max(1, Math.floor(Number(page) || 1));
    url.searchParams.set('_page', String(safePage));
    url.hash = `page=${safePage}&toolbar=1&navpanes=0&pagemode=none&zoom=page-fit`;
    return url.toString();
  } catch (err) {
    return safe;
  }
};

const extractPdfPageCount = (arrayBuffer) => {
  try {
    const text = new TextDecoder('latin1').decode(new Uint8Array(arrayBuffer));
    const matches = [...text.matchAll(/\/Count\s+(\d+)/g)];
    const values = matches
      .map((match) => Number(match[1]))
      .filter((value) => Number.isFinite(value) && value > 0);
    if (values.length === 0) {
      return null;
    }
    return Math.max(...values);
  } catch (err) {
    return null;
  }
};
const MAX_RECORD_SECONDS = 120;
const AUDIO_BITRATE = 96000;

const formatClock = (seconds) => {
  const total = Math.max(0, Math.floor(Number(seconds) || 0));
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
};

const isSupportedAudioFile = (file) => {
  if (!file) {
    return false;
  }
  const type = (file.type || '').toLowerCase();
  if (!type) {
    return true;
  }
  return type.startsWith('audio/') || type === 'video/webm';
};

const formatMessageTime = (epochMs) => {
  if (!epochMs) {
    return '';
  }
  const date = new Date(epochMs);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  return new Intl.DateTimeFormat('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(date);
};

const mapAttendanceSummary = (data) => {
  const toCount = (value) => {
    const num = Number(value);
    return Number.isFinite(num) ? Math.max(0, Math.floor(num)) : 0;
  };

  const paper = toCount(data?.paper);
  const mail = toCount(data?.mail);
  const onsite = toCount(data?.onsite);
  const fieldOnsite = toCount(data?.fieldOnsite);
  const electronic = toCount(data?.electronic);
  const paperSubmitted = toCount(data?.paperSubmitted);
  const mailSubmitted = toCount(data?.mailSubmitted);
  const onsiteSubmitted = toCount(data?.onsiteSubmitted);
  const fieldOnsiteSubmitted = toCount(data?.fieldOnsiteSubmitted);
  const electronicSubmitted = toCount(data?.electronicSubmitted);
  const attendingTotal =
    Number.isFinite(Number(data?.attendingTotal))
      ? toCount(data.attendingTotal)
      : paper + mail + onsite + fieldOnsite + electronic;
  const total =
    Number.isFinite(Number(data?.total))
      ? toCount(data.total)
      : paperSubmitted + mailSubmitted + onsiteSubmitted + electronicSubmitted;

  return {
    online: toCount(data?.online),
    paper,
    mail,
    onsite,
    fieldOnsite,
    electronic,
    paperSubmitted,
    mailSubmitted,
    onsiteSubmitted,
    fieldOnsiteSubmitted,
    electronicSubmitted,
    attendingTotal,
    total
  };
};

const niceFailReason = (code) => {
  switch (code) {
    case 'user_not_found':
      return '등록되지 않은 사용자입니다.';
    case 'phone_mismatch':
      return '등록된 휴대폰 번호와 인증 정보가 일치하지 않습니다.';
    case 'partner_db_error':
      return '회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.';
    case 'roster_empty':
      return '아직 명부가 업로드되지 않았습니다. 관리자에게 문의해 주세요.';
    case 'roster_db_error':
      return '회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.';
    case 'already_connected':
      return '이미 접속 중입니다.';
    case 'session_check_failed':
      return '접속 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.';
    case 'nice_missing_name':
      return '본인인증 이름 정보가 없습니다. 다시 시도해 주세요.';
    case 'nice_missing_birth':
      return '본인인증 생년월일 정보가 없습니다. 다시 시도해 주세요.';
    case 'nice_invalid_birth':
      return '본인인증 생년월일 형식이 올바르지 않습니다. 다시 시도해 주세요.';
    case 'nice_missing_phone':
      return '본인인증 휴대폰번호 정보가 없습니다. 다시 시도해 주세요.';
    case 'nice_phone_partial':
      return '본인인증 휴대폰번호가 마스킹되어 확인할 수 없습니다. NICE 설정을 확인해 주세요.';
    case 'nice_invalid_phone':
      return '본인인증 휴대폰번호 형식이 올바르지 않습니다. 다시 시도해 주세요.';
    case 'nice_failed':
      return '본인인증에 실패했습니다. 다시 시도해 주세요.';
    default:
      return '본인인증이 완료되지 않았습니다. 다시 시도해 주세요.';
  }
};

const getMessageDisplayName = (item) => {
  const raw = item?.senderName || item?.userName || item?.displayName || '';
  const trimmed = typeof raw === 'string' ? raw.trim() : '';
  return trimmed || '익명';
};

const MobileQuestionPanel = memo(function MobileQuestionPanel({
  messages,
  messageInput,
  chatNotice,
  onMessageInputChange,
  onSendMessage,
  messagesEndRef
}) {
  return (
    <section className="rounded-3xl border border-slate-100 bg-white/95 p-4 text-sm text-slate-900 shadow-lg">
      <div className="flex items-center">
        <div>
          <h3 className="text-base font-semibold text-slate-900">질문 목록</h3>
        </div>
      </div>

      <div className="mt-3 max-h-40 overflow-y-auto rounded-2xl border border-slate-100 bg-slate-50/70 p-3">
        <div className="flex flex-col gap-2 text-sm text-slate-700">
          {messages.length === 0 ? (
            <div className="text-xs text-slate-500">승인된 질문이 아직 없어요.</div>
          ) : null}
          {messages.map((item) => (
            <div
              key={item.id}
              className="max-w-[90%] rounded-2xl border border-slate-100 bg-white p-3 text-slate-900 shadow-sm"
            >
              <div className="flex flex-wrap items-center gap-2 text-[10px] text-slate-500">
                <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-600">
                  {getMessageDisplayName(item)}
                </span>
                {item?.senderId || item?.userId ? (
                  <span className="text-[10px] text-slate-400">
                    {item?.senderId || item?.userId}
                  </span>
                ) : null}
                <span className="text-[10px] text-slate-400">
                  {formatMessageTime(item?.createdAtEpochMs || item?.createdAt)}
                </span>
              </div>
              <p className="text-sm">{item.message}</p>
            </div>
          ))}
          <div ref={messagesEndRef} />
        </div>
      </div>

      <form onSubmit={onSendMessage} className="mt-3 grid gap-2">
        <div className="flex gap-2">
          <input
            value={messageInput}
            onChange={onMessageInputChange}
            placeholder="질문을 입력하세요"
            className="flex-1 rounded-2xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-slate-400/60"
          />
          <button
            type="submit"
            className="rounded-2xl bg-slate-900 px-4 py-2 text-xs font-semibold text-white transition hover:bg-slate-800"
          >
            보내기
          </button>
        </div>
        {chatNotice ? (
          <p className="text-xs text-slate-500">{chatNotice}</p>
        ) : (
          <p className="text-xs text-slate-500">조합원 확인 후 채팅에 표시됩니다</p>
        )}
      </form>
    </section>
  );
});

export default function App() {
  const videoRef = useRef(null);
  const hlsRef = useRef(null);
  const videoAudioStateRef = useRef({ muted: false, volume: 1 });
  const recordPreviewAudioRef = useRef(null);
  const bufferingReloadTimerRef = useRef(null);
  const liveStartReloadTimersRef = useRef([]);
  const lastReloadAtRef = useRef(0);
  const lastMediaRecoverAtRef = useRef(0);
  const currentStreamUrlRef = useRef('');
  const fallbackActiveRef = useRef(false);
  const fallbackUntilRef = useRef(0);
  const fallbackRestoreTimerRef = useRef(null);
  const reloadWindowRef = useRef({ startedAt: 0, count: 0 });
  const networkFatalWindowRef = useRef({ startedAt: 0, count: 0 });
  const liveTargetDurationRef = useRef(10);
  const lastLiveEdgeSyncAtRef = useRef(0);
  const playbackStallStartedAtRef = useRef(0);
  const lastPlaybackEventAtRef = useRef({});
  const streamLoadSeqRef = useRef(0);
  const prevLiveRef = useRef(false);
  const statusRef = useRef('Idle');
  const streamLiveRef = useRef(false);
  const watchdogFailCountRef = useRef(0);
  const RECOVERY_RELOAD_WINDOW_MS = 60000;
  const RECOVERY_RELOAD_MAX_COUNT = 5;
  const NETWORK_FATAL_WINDOW_MS = 30000;
  const NETWORK_FATAL_FALLBACK_THRESHOLD = 3;
  const FALLBACK_HOLD_MS = 120000;
  const [config, setConfig] = useState(emptyConfig);
  const [streamUrl, setStreamUrl] = useState('');
  const [status, setStatus] = useState('Idle');
  const [error, setError] = useState('');
  const [engine, setEngine] = useState('');
  const loadedUrlRef = useRef('');
  const playerWrapRef = useRef(null);
  const [isImmersive, setIsImmersive] = useState(false);
  const [isMobile, setIsMobile] = useState(false);
  const [messages, setMessages] = useState([]);
  const [messageInput, setMessageInput] = useState('');
  const [chatNotice, setChatNotice] = useState('');
  const [audioNotice, setAudioNotice] = useState('');
  const [audioToast, setAudioToast] = useState('');
  const [audioUploading, setAudioUploading] = useState(false);
  const [isAudioModalOpen, setIsAudioModalOpen] = useState(false);
  const [isSpeechRequestModalOpen, setIsSpeechRequestModalOpen] = useState(false);
  const [recordState, setRecordState] = useState('idle');
  const [recordSeconds, setRecordSeconds] = useState(0);
  const [recordBlob, setRecordBlob] = useState(null);
  const [recordPreviewUrl, setRecordPreviewUrl] = useState('');
  const [recordPreviewDurationSec, setRecordPreviewDurationSec] = useState(null);
  const audioInputRef = useRef(null);
  const mediaRecorderRef = useRef(null);
  const mediaStreamRef = useRef(null);
  const recordChunksRef = useRef([]);
  const recordTimerRef = useRef(null);
  const audioToastTimerRef = useRef(null);
  const markedPlayRef = useRef(false);
  const lastMessageIdRef = useRef(0);
  const messagesEndRef = useRef(null);
  const [viewerCount, setViewerCount] = useState(null);
  const [isQaOpen, setIsQaOpen] = useState(false);
  const [streamMeta, setStreamMeta] = useState({
    title: '',
    scheduledStart: '',
    status: '',
    statusLabel: '',
    live: false,
    minutesToStart: null
  });
  const [materials, setMaterials] = useState([]);
  const [selectedMaterial, setSelectedMaterial] = useState(null);
  const [selectedPdfPage, setSelectedPdfPage] = useState(1);
  const [selectedPdfPageCount, setSelectedPdfPageCount] = useState(null);
  const [selectedPdfMode, setSelectedPdfMode] = useState('canvas');
  const [selectedPdfLoading, setSelectedPdfLoading] = useState(false);
  const [selectedPdfRendering, setSelectedPdfRendering] = useState(false);
  const [selectedPdfError, setSelectedPdfError] = useState('');
  const [selectedPdfDocVersion, setSelectedPdfDocVersion] = useState(0);
  const [materialsLoading, setMaterialsLoading] = useState(false);
  const [materialsError, setMaterialsError] = useState('');
  const [isMaterialsPanelOpen, setIsMaterialsPanelOpen] = useState(true);
  const [niceStatus, setNiceStatus] = useState('idle');
  const [niceRequestNo, setNiceRequestNo] = useState('');
  const [niceResult, setNiceResult] = useState(null);
  const [niceError, setNiceError] = useState('');
  const [viewerAuthed, setViewerAuthed] = useState(() => getAuthHintFromStorage());
  const [viewerAuthChecked, setViewerAuthChecked] = useState(() => getAuthHintFromStorage());
  const [niceToast, setNiceToast] = useState('');
  const [niceAttempted, setNiceAttempted] = useState(false);
  const [voteChecking, setVoteChecking] = useState(false);
  const [isVoteModalOpen, setIsVoteModalOpen] = useState(false);
  const [voteModalMessage, setVoteModalMessage] = useState('');
  const [voteOpen, setVoteOpen] = useState(false);
  const [attendanceVisible, setAttendanceVisible] = useState(false);
  const [isAttendanceModalOpen, setIsAttendanceModalOpen] = useState(false);
  const [attendanceSummary, setAttendanceSummary] = useState(emptyAttendanceSummary);
  const [attendanceError, setAttendanceError] = useState('');

  // Viewer meeting flow state (post-NICE auth)
  // 'initializing' = before viewerAuthed becomes true
  // 'loading-meetings' = fetching /api/viewer/my-meetings
  // 'selecting-meeting' = 2+ meetings — user picks one
  // 'not-in-roster' = 0 meetings matched
  // 'access-closed' = chosen meeting's access_open=false
  // 'watching' = inside the viewer UI for the chosen meeting
  // 'exited' = user clicked 퇴장 — show "퇴장 완료" then redirect
  const [viewerPageState, setViewerPageState] = useState('initializing');
  const [myMeetings, setMyMeetings] = useState([]);
  const [selectedMeeting, setSelectedMeeting] = useState(null);
  const [meetingFlowError, setMeetingFlowError] = useState('');
  const [exitTimeText, setExitTimeText] = useState('');
  const [exitInFlight, setExitInFlight] = useState(false);

  useEffect(() => {
    statusRef.current = status;
  }, [status]);

  useEffect(() => {
    streamLiveRef.current = Boolean(streamMeta.live);
  }, [streamMeta.live]);
  const pdfDocumentRef = useRef(null);
  const pdfRenderTaskRef = useRef(null);
  const pdfCanvasRef = useRef(null);
  const markViewerUnauthorized = () => {
    setViewerAuthed(false);
    setViewerAuthChecked(true);
    try {
      window.sessionStorage.removeItem('viewerAuthedHint');
    } catch (err) {
      // ignore storage failures
    }
  };

  const queryUrl = useMemo(() => {
    const params = new URLSearchParams(window.location.search);
    return params.get('url') || '';
  }, []);

  const loadPdfJsLib = async () => {
    if (!pdfJsLibPromise) {
      pdfJsLibPromise = import('pdfjs-dist/legacy/build/pdf.mjs')
        .then((mod) => {
          if (mod?.GlobalWorkerOptions) {
            mod.GlobalWorkerOptions.workerSrc = PDF_WORKER_SRC;
          }
          return mod;
        })
        .catch((err) => {
          pdfJsLibPromise = null;
          throw err;
        });
    }
    return pdfJsLibPromise;
  };

  useEffect(() => {
    const controller = new AbortController();
    fetch('/api/config', { signal: controller.signal })
      .then((res) => (res.ok ? res.json() : Promise.reject(res)))
      .then((data) => {
        const next = { ...emptyConfig, ...data };
        setConfig(next);
      })
      .catch(() => {
        // streamUrl derived effect below will fall back to query string or empty
      });

    return () => controller.abort();
  }, [queryUrl]);

  // Re-derive streamUrl whenever config (specifically config.defaultStreamKey) changes,
  // so that enter-meeting → setConfig({ defaultStreamKey: meeting.streamKey, ... })
  // immediately re-points the video player at the meeting's stream URL.
  useEffect(() => {
    const fromQuery = trimUrl(queryUrl);
    if (fromQuery) {
      setStreamUrl(fromQuery);
      return;
    }
    setStreamUrl(resolveStreamUrl(config));
  }, [config, queryUrl]);

  useEffect(() => {
    let active = true;
    fetch('/api/auth/me', { cache: 'no-store' })
      .then((res) => (res.ok ? res.json() : Promise.reject(res)))
      .then((data) => {
        if (!active) {
          return;
        }
        setViewerAuthed(Boolean(data?.authenticated));
      })
      .catch(() => {
        if (!active) {
          return;
        }
        setViewerAuthed(false);
      })
      .finally(() => {
        if (!active) {
          return;
        }
        setViewerAuthChecked(true);
      });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if (!viewerAuthed) {
      markedPlayRef.current = false;
    }
  }, [viewerAuthed]);

  useEffect(() => {
    try {
      if (viewerAuthed) {
        window.sessionStorage.setItem('viewerAuthedHint', '1');
      } else {
        window.sessionStorage.removeItem('viewerAuthedHint');
      }
    } catch (err) {
      // ignore storage failures
    }
  }, [viewerAuthed]);

  useEffect(() => {
    if (!viewerAuthed) {
      return undefined;
    }
    let sent = false;
    let disconnectTimerId = null;
    const clearDisconnectTimer = () => {
      if (disconnectTimerId) {
        window.clearTimeout(disconnectTimerId);
        disconnectTimerId = null;
      }
    };
    const sendDisconnect = () => {
      if (sent) {
        return;
      }
      sent = true;
      const url = '/api/auth/disconnect?reason=browser_exit';
      try {
        if (navigator.sendBeacon) {
          // Use a small non-empty payload; some mobile browsers are unreliable with empty beacon bodies.
          const payload = new URLSearchParams({ ts: String(Date.now()) });
          navigator.sendBeacon(url, payload);
        }
      } catch (err) {
        // Ignore and fall through to fetch keepalive.
      }
      fetch(url, {
        method: 'POST',
        keepalive: true,
        credentials: 'include'
      }).catch(() => { });
    };
    const scheduleDisconnect = (delayMs = 8000) => {
      if (sent) {
        return;
      }
      clearDisconnectTimer();
      disconnectTimerId = window.setTimeout(() => {
        disconnectTimerId = null;
        sendDisconnect();
      }, Math.max(0, delayMs));
    };

    const handlePageHide = (event) => {
      // Preserve BFCache on back/forward navigation when possible.
      if (event?.persisted) {
        return;
      }
      scheduleDisconnect(0);
    };
    const handleFreeze = () => scheduleDisconnect(0);
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'hidden') {
        scheduleDisconnect(0);
        return;
      }
      clearDisconnectTimer();
      if (document.visibilityState === 'visible' && sent) {
        // If we were backgrounded and came back, reopen the access log and allow a future disconnect.
        sent = false;
        fetch('/api/auth/me', { cache: 'no-store' }).catch(() => { });
      }
    };
    const handlePageShow = () => {
      clearDisconnectTimer();
      if (sent) {
        sent = false;
        fetch('/api/auth/me', { cache: 'no-store' }).catch(() => { });
      }
    };

    window.addEventListener('pagehide', handlePageHide);
    document.addEventListener('freeze', handleFreeze);
    document.addEventListener('visibilitychange', handleVisibilityChange);
    window.addEventListener('pageshow', handlePageShow);
    return () => {
      clearDisconnectTimer();
      window.removeEventListener('pagehide', handlePageHide);
      document.removeEventListener('freeze', handleFreeze);
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      window.removeEventListener('pageshow', handlePageShow);
    };
  }, [viewerAuthed]);

  useEffect(() => {
    return () => {
      if (fallbackRestoreTimerRef.current) {
        window.clearTimeout(fallbackRestoreTimerRef.current);
        fallbackRestoreTimerRef.current = null;
      }
      if (hlsRef.current) {
        hlsRef.current.destroy();
        hlsRef.current = null;
      }
    };
  }, []);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) {
      return;
    }
    const handleWaiting = () => {
      setStatus((prev) => (prev === 'Loading' ? prev : 'Buffering'));
      if (!playbackStallStartedAtRef.current) {
        playbackStallStartedAtRef.current = Date.now();
      }
      if (isNearLiveEdge(video, 1.5)) {
        syncToLiveEdge({ autoPlay: true, cooldownMs: 1200 });
      }
    };
    const handleStalled = () => {
      setStatus((prev) => (prev === 'Loading' ? prev : 'Buffering'));
      if (!playbackStallStartedAtRef.current) {
        playbackStallStartedAtRef.current = Date.now();
      }
      if (isNearLiveEdge(video, 1.5)) {
        syncToLiveEdge({ autoPlay: true, cooldownMs: 1200 });
      }
    };
    const handlePlaying = () => {
      setStatus('Playing');
      setError('');
      networkFatalWindowRef.current = { startedAt: 0, count: 0 };
      reloadWindowRef.current = { startedAt: 0, count: 0 };
      const stallStartedAt = playbackStallStartedAtRef.current;
      if (stallStartedAt) {
        const dur = Date.now() - stallStartedAt;
        playbackStallStartedAtRef.current = 0;
        // Ignore <1s blips — those are normal playback nudges, not user-visible stalls.
        if (dur >= 1000) {
          sendPlaybackEvent('stall', dur);
        }
      }
    };
    const handleError = () => setStatus('Error');
    const handleSeeking = () => {
      if (!hlsRef.current) {
        return;
      }
      // Dragging to exact live end often stalls; snap to a safe live-sync point.
      if (isNearLiveEdge(video, 2)) {
        syncToLiveEdge({ autoPlay: false, cooldownMs: 500 });
      }
    };
    const handleSeeked = () => {
      if (!hlsRef.current || !isNearLiveEdge(video, 2)) {
        return;
      }
      try {
        hlsRef.current.startLoad();
      } catch (err) {
        // ignore
      }
      if (video.paused) {
        video.play().catch(() => { });
      }
    };

    video.addEventListener('waiting', handleWaiting);
    video.addEventListener('stalled', handleStalled);
    video.addEventListener('playing', handlePlaying);
    video.addEventListener('error', handleError);
    video.addEventListener('seeking', handleSeeking);
    video.addEventListener('seeked', handleSeeked);
    return () => {
      video.removeEventListener('waiting', handleWaiting);
      video.removeEventListener('stalled', handleStalled);
      video.removeEventListener('playing', handlePlaying);
      video.removeEventListener('error', handleError);
      video.removeEventListener('seeking', handleSeeking);
      video.removeEventListener('seeked', handleSeeked);
    };
  }, []);

  useEffect(() => {
    const handler = (event) => {
      if (!event?.data || event.data.type !== 'nice_auth') {
        return;
      }
      const origin = event.origin || '';
      const originOk =
        origin === window.location.origin ||
        origin.endsWith('.example.com');
      if (!originOk) {
        return;
      }
      const requestNo = event.data.requestNo;
      if (!requestNo) {
        return;
      }
      setNiceAttempted(true);
      if (niceRequestNo && requestNo !== niceRequestNo) {
        return;
      }
      fetch(`/api/nice/result/${encodeURIComponent(requestNo)}`)
        .then((res) => (res.ok ? res.json() : Promise.reject(res)))
        .then((data) => {
          setNiceResult(data || null);
          if (data?.status === 'SUCCESS') {
            setNiceStatus('just-verified');
            setNiceToast('본인인증이 완료되었습니다.');
            window.setTimeout(() => setNiceToast(''), 4000);
            fetch('/api/auth/me', { cache: 'no-store' })
              .then((res) => (res.ok ? res.json() : Promise.reject(res)))
              .then((me) => {
                const authed = Boolean(me?.authenticated);
                setViewerAuthed(authed);
                setViewerAuthChecked(true);
                if (authed) {
                  window.setTimeout(() => {
                    setNiceStatus('verified');
                  }, 1500);
                  return;
                }
                setNiceStatus('failed');
                setNiceError('본인인증 세션 생성에 실패했습니다. 다시 시도해 주세요.');
              })
              .catch(() => {
                setNiceStatus('failed');
                setNiceError('본인인증 세션 생성에 실패했습니다. 다시 시도해 주세요.');
              });
            setNiceError('');
          } else if (data?.status === 'FAIL') {
            setNiceStatus('failed');
            setNiceError(niceFailReason(data?.message));
          } else {
            setNiceStatus('pending');
          }
        })
        .catch(() => {
          setNiceError('본인인증 결과 조회에 실패했습니다.');
          setNiceStatus('failed');
        });
    };
    window.addEventListener('message', handler);
    return () => window.removeEventListener('message', handler);
  }, [niceRequestNo]);

  useEffect(() => {
    // Mobile-friendly resume: NICE may return in the same tab (no opener/postMessage).
    const params = new URLSearchParams(window.location.search);
    const requestNo =
      (params.get('niceRequestNo') || params.get('request_no') || '').trim();
    if (!requestNo) {
      return;
    }
    setNiceAttempted(true);
    params.delete('niceRequestNo');
    params.delete('request_no');
    params.delete('niceStatus');
    params.delete('nice_status');
    const nextQuery = params.toString();
    const nextUrl =
      window.location.pathname + (nextQuery ? `?${nextQuery}` : '') + window.location.hash;
    window.history.replaceState({}, '', nextUrl);

    setNiceRequestNo(requestNo);
    setNiceStatus('pending');
    fetch(`/api/nice/result/${encodeURIComponent(requestNo)}`)
      .then((res) => (res.ok ? res.json() : Promise.reject(res)))
      .then((data) => {
        setNiceResult(data || null);
        if (data?.status === 'SUCCESS') {
          setNiceStatus('just-verified');
          setNiceToast('본인인증이 완료되었습니다.');
          window.setTimeout(() => setNiceToast(''), 4000);
          fetch('/api/auth/me', { cache: 'no-store' })
            .then((res) => (res.ok ? res.json() : Promise.reject(res)))
            .then((me) => {
              const authed = Boolean(me?.authenticated);
              setViewerAuthed(authed);
              setViewerAuthChecked(true);
              if (authed) {
                window.setTimeout(() => setNiceStatus('verified'), 1500);
                setNiceError('');
                return;
              }
              setNiceStatus('failed');
              setNiceError('본인인증 세션 생성에 실패했습니다. 다시 시도해 주세요.');
            })
            .catch(() => {
              setNiceStatus('failed');
              setNiceError('본인인증 세션 생성에 실패했습니다. 다시 시도해 주세요.');
            });
          return;
        }
        if (data?.status === 'FAIL') {
          setNiceStatus('failed');
          setNiceError(niceFailReason(data?.message));
          return;
        }
        setNiceStatus('pending');
      })
      .catch(() => {
        setNiceStatus('failed');
        setNiceError('본인인증 결과 조회에 실패했습니다.');
      });
  }, []);

  // After NICE auth completes, resolve which meeting(s) this viewer belongs to
  // via the roster, then auto-enter (1 match) or show a selector (2+).
  // Skips when we're already past the gate (watching / exited / etc.).
  useEffect(() => {
    if (!viewerAuthed) {
      setViewerPageState('initializing');
      return undefined;
    }
    if (
      viewerPageState === 'watching' ||
      viewerPageState === 'exited' ||
      viewerPageState === 'access-closed' ||
      viewerPageState === 'not-in-roster' ||
      viewerPageState === 'selecting-meeting'
    ) {
      return undefined;
    }
    let active = true;
    setViewerPageState('loading-meetings');
    setMeetingFlowError('');
    fetch('/api/viewer/my-meetings', { credentials: 'include', cache: 'no-store' })
      .then((res) => (res.ok ? res.json() : Promise.reject(res)))
      .then((data) => {
        if (!active) return;
        const meetings = Array.isArray(data?.meetings) ? data.meetings : [];
        setMyMeetings(meetings);
        if (meetings.length === 0) {
          setViewerPageState('not-in-roster');
        } else if (meetings.length === 1) {
          enterMeeting(meetings[0]);
        } else {
          setViewerPageState('selecting-meeting');
        }
      })
      .catch(() => {
        if (!active) return;
        setMeetingFlowError('회의 정보를 불러오지 못했습니다.');
        setViewerPageState('not-in-roster');
      });
    return () => {
      active = false;
    };
  }, [viewerAuthed]);

  const enterMeeting = (meeting) => {
    if (!meeting || !meeting.streamKey) {
      setMeetingFlowError('잘못된 회의 정보입니다.');
      setViewerPageState('access-closed');
      return;
    }
    setViewerPageState('loading-meetings');
    fetch('/api/viewer/enter-meeting', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ streamKey: meeting.streamKey })
    })
      .then(async (res) => {
        if (res.ok) {
          let body = null;
          try {
            body = await res.json();
          } catch (_) {
            body = meeting;
          }
          setSelectedMeeting(body || meeting);
          // Rewriting config so every downstream useEffect that reads
          // config.defaultStreamKey re-fires with the meeting's streamKey.
          setConfig((prev) => ({
            ...prev,
            defaultStreamKey: body?.streamKey || meeting.streamKey,
            defaultUrl: ''
          }));
          setViewerPageState('watching');
          return;
        }
        let errorCode = '';
        try {
          const errBody = await res.json();
          errorCode = errBody?.error || '';
        } catch (_) {
          // ignore
        }
        if (errorCode === 'access_closed') {
          setMeetingFlowError('접속 가능한 시간이 아닙니다.');
          setViewerPageState('access-closed');
        } else if (errorCode === 'not_in_roster') {
          setViewerPageState('not-in-roster');
        } else {
          setMeetingFlowError('회의 입장에 실패했습니다.');
          setViewerPageState('access-closed');
        }
      })
      .catch(() => {
        setMeetingFlowError('회의 입장에 실패했습니다.');
        setViewerPageState('access-closed');
      });
  };

  const handleBackToAuth = async () => {
    // Must hit /api/auth/logout (not /disconnect) — disconnect only closes the
    // access_log row but leaves the in-memory session alive, so /api/auth/me would
    // still report authed=true on reload and we'd loop right back into enter-meeting.
    try {
      await fetch('/api/auth/logout', {
        method: 'POST',
        credentials: 'include',
        keepalive: true
      });
    } catch (_) {
      // ignore network failures — we still reload to the auth screen
    }
    try {
      window.sessionStorage.removeItem('viewerAuthedHint');
    } catch (_) {
      // ignore
    }
    window.location.href = '/';
  };

  const handleViewerExit = async () => {
    if (exitInFlight || viewerPageState === 'exited') {
      return;
    }
    setExitInFlight(true);
    const now = new Date();
    const hh = String(now.getHours()).padStart(2, '0');
    const mm = String(now.getMinutes()).padStart(2, '0');
    setExitTimeText(`${hh}:${mm}`);
    try {
      // Must hit /logout (not /disconnect) — disconnect only closes the access_log row
      // but leaves the in-memory session alive, so the redirect to '/' would re-authenticate
      // and (for users in exactly one meeting) auto-enter that meeting again.
      await fetch('/api/auth/logout?reason=user_exit', {
        method: 'POST',
        credentials: 'include',
        keepalive: true
      });
    } catch (_) {
      // ignore — even if the network call fails we still proceed to exit UI
    }
    setViewerPageState('exited');
    // Give the user a moment to read the "퇴장 완료" message, then send them home.
    window.setTimeout(() => {
      try {
        window.sessionStorage.removeItem('viewerAuthedHint');
      } catch (_) {
        // ignore
      }
      window.location.href = '/';
    }, 3000);
  };

  useEffect(() => {
    if (!viewerAuthed) {
      return undefined;
    }
    const streamKey = (config.defaultStreamKey || 'stream').trim();
    if (!streamKey) {
      return undefined;
    }
    let active = true;
    const loadVoteStatus = () => {
      fetch(`/api/vote/status?streamKey=${encodeURIComponent(streamKey)}`, { cache: 'no-store' })
        .then((res) => {
          if (!res.ok) {
            if (res.status === 401) {
              markViewerUnauthorized();
            }
            return Promise.reject(res);
          }
          return res.json();
        })
        .then((data) => {
          if (!active) {
            return;
          }
          setVoteOpen(Boolean(data?.open));
        })
        .catch(() => {
          if (!active) {
            return;
          }
          setVoteOpen(false);
        });
    };
    const loadAttendance = () => {
      fetch(`/api/attendance?streamKey=${encodeURIComponent(streamKey)}`, { cache: 'no-store' })
        .then((res) => {
          if (!res.ok) {
            if (res.status === 401) {
              markViewerUnauthorized();
            }
            return Promise.reject(res);
          }
          return res.json();
        })
        .then((data) => {
          if (!active) {
            return;
          }
          setAttendanceSummary(mapAttendanceSummary(data));
          setAttendanceError('');
        })
        .catch(() => {
          if (!active) {
            return;
          }
          setAttendanceError('출석 현황을 불러오지 못했습니다.');
        });
    };
    const loadAttendanceVisibility = () => {
      fetch(`/api/attendance-visibility/status?streamKey=${encodeURIComponent(streamKey)}`, { cache: 'no-store' })
        .then((res) => {
          if (!res.ok) {
            if (res.status === 401) {
              markViewerUnauthorized();
            }
            return Promise.reject(res);
          }
          return res.json();
        })
        .then((data) => {
          if (!active) {
            return;
          }
          setAttendanceVisible(Boolean(data?.attendanceVisible));
        })
        .catch(() => {
          if (!active) {
            return;
          }
          setAttendanceVisible(false);
        });
    };
    const loadMeta = () => {
      fetch(`/api/stream-meta?streamKey=${encodeURIComponent(streamKey)}`)
        .then((res) => {
          if (!res.ok) {
            if (res.status === 401) {
              markViewerUnauthorized();
            }
            return Promise.reject(res);
          }
          return res.json();
        })
        .then((data) => {
          if (!active) {
            return;
          }
          setStreamMeta({
            title: data?.title || '',
            scheduledStart: data?.scheduledStart || '',
            status: data?.status || '',
            statusLabel: data?.statusLabel || '',
            live: Boolean(data?.live),
            minutesToStart:
              typeof data?.minutesToStart === 'number' ? data.minutesToStart : null
          });
        })
        .catch(() => { });
    };
    loadMeta();
    loadVoteStatus();
    loadAttendance();
    loadAttendanceVisibility();
    const id = window.setInterval(loadMeta, 30000);
    const voteId = window.setInterval(loadVoteStatus, 10000);
    const attendanceId = window.setInterval(loadAttendance, 30000);
    const attendanceVisibilityId = window.setInterval(loadAttendanceVisibility, 10000);
    return () => {
      active = false;
      window.clearInterval(id);
      window.clearInterval(voteId);
      window.clearInterval(attendanceId);
      window.clearInterval(attendanceVisibilityId);
    };
  }, [config.defaultStreamKey, viewerAuthed]);

  useEffect(() => {
    if (!viewerAuthed) {
      setMaterials([]);
      setMaterialsError('');
      setMaterialsLoading(false);
      setAttendanceVisible(false);
      setAttendanceSummary(emptyAttendanceSummary);
      setAttendanceError('');
      return undefined;
    }
    const streamKey = (config.defaultStreamKey || 'stream').trim();
    if (!streamKey) {
      return undefined;
    }
    let active = true;
    const loadMaterials = () => {
      setMaterialsLoading(true);
      fetch(`/api/materials?streamKey=${encodeURIComponent(streamKey)}&limit=200`)
        .then((res) => {
          if (!res.ok) {
            if (res.status === 401) {
              markViewerUnauthorized();
            }
            return Promise.reject(res);
          }
          return res.json();
        })
        .then((data) => {
          if (!active) {
            return;
          }
          setMaterials(Array.isArray(data) ? data : []);
          setMaterialsError('');
        })
        .catch(() => {
          if (!active) {
            return;
          }
          setMaterials([]);
          setMaterialsError('자료를 불러오지 못했습니다.');
        })
        .finally(() => {
          if (!active) {
            return;
          }
          setMaterialsLoading(false);
        });
    };
    loadMaterials();
    const id = window.setInterval(loadMaterials, 15000);
    return () => {
      active = false;
      window.clearInterval(id);
    };
  }, [config.defaultStreamKey, viewerAuthed]);

  useEffect(() => {
    if (attendanceVisible) {
      setIsMaterialsPanelOpen(true);
    }
  }, [attendanceVisible]);

  useEffect(() => {
    if (!selectedMaterial || selectedMaterial.type !== 'pdf') {
      setSelectedPdfPageCount(null);
      setSelectedPdfMode('canvas');
      setSelectedPdfLoading(false);
      setSelectedPdfRendering(false);
      setSelectedPdfError('');
      setSelectedPdfDocVersion(0);
      if (pdfRenderTaskRef.current) {
        try {
          pdfRenderTaskRef.current.cancel();
        } catch (err) {
          // ignore
        }
        pdfRenderTaskRef.current = null;
      }
      if (pdfDocumentRef.current) {
        pdfDocumentRef.current.destroy().catch(() => { });
        pdfDocumentRef.current = null;
      }
      return undefined;
    }

    const safeUrl = safeExternalUrl(selectedMaterial.url);
    if (!safeUrl) {
      setSelectedPdfPageCount(null);
      setSelectedPdfLoading(false);
      setSelectedPdfRendering(false);
      setSelectedPdfError('PDF 파일을 불러올 수 없습니다.');
      return undefined;
    }

    const controller = new AbortController();
    let active = true;
    setSelectedPdfMode('canvas');
    setSelectedPdfLoading(true);
    setSelectedPdfError('');
    setSelectedPdfPageCount(null);
    setSelectedPdfDocVersion(0);

    if (pdfRenderTaskRef.current) {
      try {
        pdfRenderTaskRef.current.cancel();
      } catch (err) {
        // ignore
      }
      pdfRenderTaskRef.current = null;
    }
    if (pdfDocumentRef.current) {
      pdfDocumentRef.current.destroy().catch(() => { });
      pdfDocumentRef.current = null;
    }

    (async () => {
      try {
        const response = await fetch(safeUrl, {
          credentials: 'include',
          cache: 'no-store',
          signal: controller.signal
        });
        if (!response.ok) {
          throw new Error('pdf_fetch_failed');
        }
        const buffer = await response.arrayBuffer();
        if (!active) {
          return;
        }
        const scannedCount = extractPdfPageCount(buffer);
        if (scannedCount && scannedCount > 0) {
          setSelectedPdfPageCount(scannedCount);
        }
        const pdfjsLib = await loadPdfJsLib();
        if (!active) {
          return;
        }
        const loadingTask = pdfjsLib.getDocument({
          data: new Uint8Array(buffer)
        });
        const doc = await loadingTask.promise;
        if (!active) {
          doc.destroy().catch(() => { });
          return;
        }
        pdfDocumentRef.current = doc;
        setSelectedPdfMode('canvas');
        setSelectedPdfDocVersion((prev) => prev + 1);
        setSelectedPdfPageCount(Math.max(1, Number(doc.numPages) || 1));
        setSelectedPdfPage(1);
      } catch (err) {
        if (!active) {
          return;
        }
        console.error('[PDF] load failed', err);
        const detail = `${err?.name || 'Error'}${err?.message ? `: ${err.message}` : ''}`;
        setSelectedPdfMode('canvas');
        setSelectedPdfRendering(false);
        setSelectedPdfError(`PDF 파일을 불러오지 못했습니다. (${detail})`);
      } finally {
        if (!active) {
          return;
        }
        setSelectedPdfLoading(false);
      }
    })();

    return () => {
      active = false;
      controller.abort();
    };
  }, [selectedMaterial]);

  useEffect(() => {
    if (selectedPdfPageCount && selectedPdfPage > selectedPdfPageCount) {
      setSelectedPdfPage(selectedPdfPageCount);
    }
  }, [selectedPdfPage, selectedPdfPageCount]);

  useEffect(() => {
    if (!selectedMaterial || selectedMaterial.type !== 'pdf') {
      return undefined;
    }
    if (selectedPdfMode !== 'canvas') {
      setSelectedPdfRendering(false);
      return undefined;
    }
    if (selectedPdfLoading) {
      setSelectedPdfRendering(false);
      return undefined;
    }
    const doc = pdfDocumentRef.current;
    const canvas = pdfCanvasRef.current;
    if (!doc || !canvas) {
      return undefined;
    }

    const docPageCount = Math.max(1, Number(doc.numPages) || 1);
    if (!selectedPdfPageCount || selectedPdfPageCount !== docPageCount) {
      setSelectedPdfPageCount(docPageCount);
    }

    let active = true;
    const pageNumber = Math.max(1, Math.min(docPageCount, selectedPdfPage));
    setSelectedPdfRendering(true);

    if (pdfRenderTaskRef.current) {
      try {
        pdfRenderTaskRef.current.cancel();
      } catch (err) {
        // ignore
      }
      pdfRenderTaskRef.current = null;
    }

    (async () => {
      try {
        const page = await doc.getPage(pageNumber);
        if (!active) {
          return;
        }
        const baseViewport = page.getViewport({ scale: 1 });
        const containerWidth = Math.max(
          300,
          Math.floor(canvas.parentElement?.clientWidth || baseViewport.width)
        );
        const scale = containerWidth / baseViewport.width;
        const viewport = page.getViewport({ scale });
        const outputScale = window.devicePixelRatio || 1;
        const context = canvas.getContext('2d', { alpha: false });
        if (!context) {
          throw new Error('pdf_canvas_context_missing');
        }

        canvas.width = Math.floor(viewport.width * outputScale);
        canvas.height = Math.floor(viewport.height * outputScale);
        canvas.style.width = `${Math.floor(viewport.width)}px`;
        canvas.style.height = `${Math.floor(viewport.height)}px`;
        context.setTransform(outputScale, 0, 0, outputScale, 0, 0);
        context.clearRect(0, 0, viewport.width, viewport.height);

        const renderTask = page.render({
          canvasContext: context,
          viewport
        });
        pdfRenderTaskRef.current = renderTask;
        await renderTask.promise;
      } catch (err) {
        if (!active) {
          return;
        }
        if (err?.name === 'RenderingCancelledException') {
          return;
        }
        console.error('[PDF] render failed', err);
        if (typeof err?.message === 'string' && err.message.includes('Invalid page request')) {
          setSelectedPdfPage((prev) => Math.max(1, Math.min(docPageCount, prev)));
          return;
        }
        const detail = `${err?.name || 'Error'}${err?.message ? `: ${err.message}` : ''}`;
        setSelectedPdfError(`PDF 페이지를 표시하지 못했습니다. (${detail})`);
      } finally {
        if (!active) {
          return;
        }
        setSelectedPdfRendering(false);
      }
    })();

    return () => {
      active = false;
      if (pdfRenderTaskRef.current) {
        try {
          pdfRenderTaskRef.current.cancel();
        } catch (err) {
          // ignore
        }
        pdfRenderTaskRef.current = null;
      }
    };
  }, [selectedMaterial, selectedPdfMode, selectedPdfLoading, selectedPdfPage, selectedPdfPageCount, selectedPdfDocVersion]);

  useEffect(() => {
    return () => {
      if (pdfRenderTaskRef.current) {
        try {
          pdfRenderTaskRef.current.cancel();
        } catch (err) {
          // ignore
        }
        pdfRenderTaskRef.current = null;
      }
      if (pdfDocumentRef.current) {
        pdfDocumentRef.current.destroy().catch(() => { });
        pdfDocumentRef.current = null;
      }
    };
  }, []);

  const resetPlayer = () => {
    if (hlsRef.current) {
      hlsRef.current.destroy();
      hlsRef.current = null;
    }
    const video = videoRef.current;
    if (video) {
      video.pause();
      video.removeAttribute('src');
      video.load();
    }
    setEngine('');
    loadedUrlRef.current = '';
    liveTargetDurationRef.current = 10;
  };

  // Fire-and-forget playback telemetry. Server only logs to journalctl; no UI side effects.
  // Throttled per type so a flapping client doesn't spam the backend log.
  const sendPlaybackEvent = (type, valueMs = null, detail = '') => {
    if (!viewerAuthed) return;
    const now = Date.now();
    const last = lastPlaybackEventAtRef.current[type] || 0;
    if (now - last < 1000) return;
    lastPlaybackEventAtRef.current[type] = now;
    try {
      fetch('/api/viewer/playback-event', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        keepalive: true,
        body: JSON.stringify({
          type,
          valueMs: valueMs == null ? null : Math.max(0, Math.round(valueMs)),
          detail: detail ? String(detail).slice(0, 80) : ''
        })
      }).catch(() => {});
    } catch (_) {
      // ignore — telemetry is best-effort
    }
  };

  const forceReloadStream = (autoPlay = false, targetUrl = '') => {
    const url = trimUrl(targetUrl) || trimUrl(currentStreamUrlRef.current) || trimUrl(streamUrl);
    if (!url) {
      return;
    }
    currentStreamUrlRef.current = url;
    loadedUrlRef.current = '';
    loadStream(url, autoPlay);
  };

  const requestStreamReload = (
    autoPlay = false,
    minIntervalMs = 0,
    targetUrl = '',
    bypassRecoveryWindow = false
  ) => {
    const url = trimUrl(targetUrl) || trimUrl(currentStreamUrlRef.current) || trimUrl(streamUrl);
    if (!url) {
      return false;
    }
    const now = Date.now();
    if (minIntervalMs > 0 && now - lastReloadAtRef.current < minIntervalMs) {
      return false;
    }
    if (!bypassRecoveryWindow) {
      const windowState = reloadWindowRef.current;
      if (now - windowState.startedAt > RECOVERY_RELOAD_WINDOW_MS) {
        windowState.startedAt = now;
        windowState.count = 0;
      }
      if (windowState.count >= RECOVERY_RELOAD_MAX_COUNT) {
        return false;
      }
      windowState.count += 1;
    }
    lastReloadAtRef.current = now;
    forceReloadStream(autoPlay, url);
    sendPlaybackEvent('reload');
    return true;
  };

  const schedulePrimaryStreamRestore = () => {
    if (!fallbackActiveRef.current) {
      return;
    }
    const primaryUrl = trimUrl(streamUrl);
    if (!primaryUrl) {
      return;
    }
    if (fallbackRestoreTimerRef.current) {
      window.clearTimeout(fallbackRestoreTimerRef.current);
      fallbackRestoreTimerRef.current = null;
    }
    const delayMs = Math.max(1000, fallbackUntilRef.current - Date.now());
    fallbackRestoreTimerRef.current = window.setTimeout(() => {
      fallbackRestoreTimerRef.current = null;
      if (!fallbackActiveRef.current || !streamLiveRef.current) {
        return;
      }
      if (!requestStreamReload(true, 12000, primaryUrl, true)) {
        return;
      }
      fallbackActiveRef.current = false;
      currentStreamUrlRef.current = primaryUrl;
    }, delayMs);
  };

  const trySwitchToOriginFallback = (autoPlay = true) => {
    if (fallbackActiveRef.current) {
      return false;
    }
    const currentUrl = trimUrl(currentStreamUrlRef.current) || trimUrl(streamUrl);
    const fallbackUrl = deriveOriginFallbackUrl(currentUrl);
    if (!fallbackUrl) {
      return false;
    }
    fallbackActiveRef.current = true;
    fallbackUntilRef.current = Date.now() + FALLBACK_HOLD_MS;
    currentStreamUrlRef.current = fallbackUrl;
    if (!requestStreamReload(autoPlay, 3000, fallbackUrl, true)) {
      return false;
    }
    let host = '';
    try { host = new URL(fallbackUrl).host; } catch (_) {}
    sendPlaybackEvent('origin_fallback', null, host);
    schedulePrimaryStreamRestore();
    return true;
  };

  const getSeekableRange = (video) => {
    if (!video || !video.seekable || video.seekable.length === 0) {
      return null;
    }
    const last = video.seekable.length - 1;
    const start = video.seekable.start(last);
    const end = video.seekable.end(last);
    if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) {
      return null;
    }
    return { start, end };
  };

  const getLiveEdgeSafetyOffsetSec = () => {
    const targetDuration = Number(liveTargetDurationRef.current);
    const base = Number.isFinite(targetDuration) && targetDuration > 0 ? targetDuration : 10;
    return Math.max(2.5, Math.min(18, base * 1.2));
  };

  const getPreferredLivePosition = (video) => {
    const range = getSeekableRange(video);
    if (!range) {
      return null;
    }
    const liveSyncPosition = Number(hlsRef.current?.liveSyncPosition);
    const fallback = range.end - getLiveEdgeSafetyOffsetSec();
    const raw = Number.isFinite(liveSyncPosition) ? liveSyncPosition : fallback;
    const maxSafe = Math.max(range.start, range.end - 0.1);
    return Math.min(maxSafe, Math.max(range.start, raw));
  };

  const isNearLiveEdge = (video, extraWindowSec = 0) => {
    const range = getSeekableRange(video);
    if (!range) {
      return false;
    }
    return range.end - video.currentTime <= getLiveEdgeSafetyOffsetSec() + extraWindowSec;
  };

  const syncToLiveEdge = ({ autoPlay = true, cooldownMs = 800 } = {}) => {
    const video = videoRef.current;
    if (!video || !hlsRef.current) {
      return false;
    }
    const now = Date.now();
    if (now - lastLiveEdgeSyncAtRef.current < cooldownMs) {
      return false;
    }
    const next = getPreferredLivePosition(video);
    if (!Number.isFinite(next)) {
      return false;
    }
    if (Math.abs(video.currentTime - next) < 0.35) {
      return false;
    }
    try {
      video.currentTime = next;
      lastLiveEdgeSyncAtRef.current = now;
      try {
        hlsRef.current.startLoad();
      } catch (err) {
        // ignore
      }
      if (autoPlay && video.paused) {
        video.play().catch(() => { });
      }
      return true;
    } catch (err) {
      return false;
    }
  };

  const loadStream = async (targetUrl, autoPlay = false) => {
    const loadSeq = ++streamLoadSeqRef.current;
    const normalizedUrl = trimUrl(targetUrl);
    if (!normalizedUrl) {
      setStatus('Idle');
      return;
    }
    currentStreamUrlRef.current = normalizedUrl;

    if (loadedUrlRef.current === normalizedUrl) {
      if (autoPlay && videoRef.current) {
        videoRef.current.play().catch(() => { });
      }
      return;
    }

    setError('');
    setStatus('Loading');
    resetPlayer();
    loadedUrlRef.current = normalizedUrl;

    const video = videoRef.current;
    if (!video) {
      setError('Video element not ready.');
      setStatus('Error');
      return;
    }

    // Prefer native playback first on Safari/iOS.
    if (video.canPlayType('application/vnd.apple.mpegurl')) {
      if (loadSeq !== streamLoadSeqRef.current) {
        return;
      }
      setEngine('native');
      video.src = normalizedUrl;
      video.addEventListener(
        'loadedmetadata',
        () => {
          setStatus('Ready');
          if (autoPlay) {
            video.play().catch(() => { });
          }
        },
        { once: true }
      );
      return;
    }

    let HlsLib = null;
    try {
      HlsLib = await loadHlsLib();
    } catch (err) {
      if (loadSeq !== streamLoadSeqRef.current) {
        return;
      }
      setStatus('Error');
      setError('HLS player를 불러오지 못했습니다.');
      return;
    }

    if (loadSeq !== streamLoadSeqRef.current) {
      return;
    }

    if (HlsLib?.isSupported && HlsLib.isSupported()) {
      const searchParams = new URLSearchParams(window.location.search);
      const debugHls =
        searchParams.get('hlsDebug') === '1' ||
        searchParams.get('hlsDebug') === 'true' ||
        searchParams.get('hlsDebug') === 'yes';
      const hls = new HlsLib({
        debug: debugHls,
        // Stability > ultra-low-latency for meetings.
        lowLatencyMode: false,
        // Wowza default HLS chunk size is often 10s, so counts can create huge latency.
        // Use explicit seconds to keep the delay reasonable while staying resilient.
        liveSyncDuration: 45,
        liveMaxLatencyDuration: 120,
        liveSyncOnStallIncrease: 2,
        maxLiveSyncPlaybackRate: 1.2,
        maxBufferLength: 120,
        maxBufferHole: 0.5,
        backBufferLength: 180,
        startFragPrefetch: true,
        detectStallWithCurrentTimeMs: 2000,
        nudgeOffset: 0.2,
        nudgeMaxRetry: 10,
        nudgeOnVideoHole: true,
        manifestLoadingTimeOut: 20000,
        manifestLoadingMaxRetry: 10,
        manifestLoadingRetryDelay: 750,
        manifestLoadingMaxRetryTimeout: 60000,
        levelLoadingTimeOut: 20000,
        levelLoadingMaxRetry: 10,
        levelLoadingRetryDelay: 750,
        levelLoadingMaxRetryTimeout: 60000,
        fragLoadingTimeOut: 60000,
        fragLoadingMaxRetry: 10,
        fragLoadingRetryDelay: 1000,
        fragLoadingMaxRetryTimeout: 60000
      });
      hlsRef.current = hls;
      setEngine('hls.js');

      hls.on(HlsLib.Events.MANIFEST_PARSED, () => {
        setStatus('Ready');
        if (autoPlay) {
          video.play().catch(() => { });
        }
      });

      hls.on(HlsLib.Events.LEVEL_LOADED, (_, data) => {
        const targetDuration = Number(data?.details?.targetduration);
        if (Number.isFinite(targetDuration) && targetDuration > 0) {
          liveTargetDurationRef.current = targetDuration;
        }
      });

      hls.on(HlsLib.Events.ERROR, (_, data) => {
        const details = data?.details;
        const type = data?.type;

        if (debugHls) {
          const responseCode = data?.response?.code;
          const url = data?.url || data?.context?.url;
          // Keep this compact; avoid dumping whole objects into logs by default.
          // eslint-disable-next-line no-console
          console.warn('[hls]', {
            type,
            details,
            fatal: Boolean(data?.fatal),
            responseCode,
            url
          });
        }

        if (
          details === HlsLib.ErrorDetails.BUFFER_STALLED_ERROR ||
          details === HlsLib.ErrorDetails.BUFFER_NUDGE_ON_STALL
        ) {
          setStatus((prev) => (prev === 'Loading' ? prev : 'Buffering'));
          // Kick the loader in case we're in a weird paused/retry state.
          try {
            hls.startLoad();
          } catch (err) {
            // ignore
          }
          const maybeVideo = videoRef.current;
          if (maybeVideo && maybeVideo.paused) {
            maybeVideo.play().catch(() => { });
          }
          if (maybeVideo && isNearLiveEdge(maybeVideo, 2)) {
            syncToLiveEdge({ autoPlay: true, cooldownMs: 1200 });
          }
          return;
        }

        const message = `${type || 'Error'}: ${details || 'unknown'}`;
        setError(message);
        if (!data?.fatal) {
          return;
        }

        // Telemetry on fatal HLS error (network/media). Backend only logs.
        if (type === HlsLib.ErrorTypes.NETWORK_ERROR) {
          sendPlaybackEvent('fatal_network', null, details || '');
        } else if (type === HlsLib.ErrorTypes.MEDIA_ERROR) {
          sendPlaybackEvent('fatal_media', null, details || '');
        }

        // Attempt in-place recovery for common live streaming hiccups.
        if (type === HlsLib.ErrorTypes.NETWORK_ERROR) {
          setStatus('Reconnecting');
          const nowMs = Date.now();
          const networkWindow = networkFatalWindowRef.current;
          if (nowMs - networkWindow.startedAt > NETWORK_FATAL_WINDOW_MS) {
            networkWindow.startedAt = nowMs;
            networkWindow.count = 0;
          }
          networkWindow.count += 1;
          if (networkWindow.count >= NETWORK_FATAL_FALLBACK_THRESHOLD) {
            if (trySwitchToOriginFallback(true)) {
              networkWindow.startedAt = 0;
              networkWindow.count = 0;
              return;
            }
          }
          try {
            hls.startLoad();
            return;
          } catch (err) {
            // fall through to full reset
          }
          if (requestStreamReload(true, 8000)) {
            return;
          }
        } else {
          networkFatalWindowRef.current = { startedAt: 0, count: 0 };
        }
        if (type === HlsLib.ErrorTypes.MEDIA_ERROR) {
          setStatus('Recovering');
          try {
            hls.recoverMediaError();
            return;
          } catch (err) {
            // fall through to full reset
          }
        }

        setStatus('Error');
        hls.destroy();
        hlsRef.current = null;
      });

      hls.loadSource(normalizedUrl);
      hls.attachMedia(video);
      return;
    }

    setStatus('Error');
    setError('HLS is not supported in this browser.');
  };

  useEffect(() => {
    if (!viewerAuthed) {
      if (fallbackRestoreTimerRef.current) {
        window.clearTimeout(fallbackRestoreTimerRef.current);
        fallbackRestoreTimerRef.current = null;
      }
      fallbackActiveRef.current = false;
      fallbackUntilRef.current = 0;
      currentStreamUrlRef.current = '';
      resetPlayer();
      setStatus('Idle');
      return;
    }
    const primaryUrl = trimUrl(streamUrl);
    if (!primaryUrl) {
      return;
    }
    if (fallbackRestoreTimerRef.current) {
      window.clearTimeout(fallbackRestoreTimerRef.current);
      fallbackRestoreTimerRef.current = null;
    }
    fallbackActiveRef.current = false;
    fallbackUntilRef.current = 0;
    networkFatalWindowRef.current = { startedAt: 0, count: 0 };
    currentStreamUrlRef.current = primaryUrl;
    loadStream(primaryUrl, false);
  }, [streamUrl, viewerAuthed]);

  useEffect(() => {
    return () => {
      if (liveStartReloadTimersRef.current.length > 0) {
        liveStartReloadTimersRef.current.forEach((id) => window.clearTimeout(id));
        liveStartReloadTimersRef.current = [];
      }
    };
  }, []);

  useEffect(() => {
    const isLiveNow = Boolean(streamMeta.live);
    const wasLive = prevLiveRef.current;
    prevLiveRef.current = isLiveNow;

    if (!viewerAuthed || !trimUrl(streamUrl)) {
      return;
    }
    if (!isLiveNow || wasLive) {
      return;
    }

    // Guard: if the player is already playing or in the middle of an initial load
    // (e.g., the user just entered a meeting where OBS was already broadcasting),
    // the playlist is fresh and a reload would just produce a visible interruption
    // a few seconds after the user starts watching. The original purpose of this
    // effect was to refresh the playlist for users who were waiting on a stalled
    // page while the broadcast started — not to disrupt active viewers.
    const currentStatus = statusRef.current;
    if (currentStatus === 'Playing' || currentStatus === 'Loading') {
      return;
    }

    if (liveStartReloadTimersRef.current.length > 0) {
      liveStartReloadTimersRef.current.forEach((id) => window.clearTimeout(id));
      liveStartReloadTimersRef.current = [];
    }

    // LIVE 전환 직후에는 플레이리스트 반영이 지연될 수 있어 짧게 재시도한다.
    setError('');
    requestStreamReload(true, 1500);
    const retryDelays = [6000, 14000];
    liveStartReloadTimersRef.current = retryDelays.map((delayMs) =>
      window.setTimeout(() => {
        if (!streamLiveRef.current || statusRef.current === 'Playing') {
          return;
        }
        requestStreamReload(true, 6000);
      }, delayMs)
    );
  }, [streamMeta.live, viewerAuthed, streamUrl]);

  useEffect(() => {
    if (!viewerAuthed || !streamMeta.live || !trimUrl(streamUrl)) {
      return undefined;
    }
    const unstableStates = new Set(['Loading', 'Buffering', 'Reconnecting', 'Recovering']);
    const tick = () => {
      const video = videoRef.current;
      if (!video) {
        return;
      }
      const currentStatus = statusRef.current;
      if (currentStatus === 'Playing' || unstableStates.has(currentStatus)) {
        watchdogFailCountRef.current = 0;
        return;
      }
      const noSource = !video.src;
      const hardError = Boolean(video.error) || currentStatus === 'Error';
      const notReadyAtStart = video.readyState === 0 && (video.currentTime || 0) < 0.5;
      if (noSource || hardError || notReadyAtStart) {
        watchdogFailCountRef.current += 1;
        if (watchdogFailCountRef.current < 2) {
          return;
        }
        requestStreamReload(false, 12000);
        return;
      }
      watchdogFailCountRef.current = 0;
    };

    tick();
    const id = window.setInterval(tick, 10000);
    return () => window.clearInterval(id);
  }, [viewerAuthed, streamMeta.live, streamUrl]);

  useEffect(() => {
    // If we're stuck buffering for too long, force-reload the HLS pipeline.
    // This helps with transient CDN/origin hiccups (stale playlist, dropped segment, etc).
    const isBuffering =
      status === 'Buffering' || status === 'Reconnecting' || status === 'Recovering';
    if (!viewerAuthed || !isBuffering) {
      if (bufferingReloadTimerRef.current) {
        window.clearTimeout(bufferingReloadTimerRef.current);
        bufferingReloadTimerRef.current = null;
      }
      return undefined;
    }
    if (bufferingReloadTimerRef.current) {
      return undefined;
    }
    bufferingReloadTimerRef.current = window.setTimeout(() => {
      bufferingReloadTimerRef.current = null;
      const video = videoRef.current;
      // readyState < 3 means we likely don't have enough buffered data to play.
      if (!video || video.readyState >= 3) {
        return;
      }
      if (!requestStreamReload(true, 30000)) {
        return;
      }
      setStatus('Reconnecting');
    }, 20000);
    return () => {
      if (bufferingReloadTimerRef.current) {
        window.clearTimeout(bufferingReloadTimerRef.current);
        bufferingReloadTimerRef.current = null;
      }
    };
  }, [status, viewerAuthed, streamUrl]);

  useEffect(() => {
    if (!viewerAuthed) {
      setViewerCount(null);
      return undefined;
    }
    let isMounted = true;
    const loadStats = async () => {
      try {
        const response = await fetch('/api/stats');
        if (!response.ok) {
          if (response.status === 401) {
            markViewerUnauthorized();
          }
          if (isMounted) {
            setViewerCount(null);
          }
          return;
        }
        const data = await response.json();
        const hls = typeof data?.hlsConnections === 'number' ? data.hlsConnections : null;
        if (isMounted) {
          setViewerCount(hls);
        }
      } catch (err) {
        if (isMounted) {
          setViewerCount(null);
        }
      }
    };
    loadStats();
    const id = window.setInterval(loadStats, 15000);
    return () => {
      isMounted = false;
      window.clearInterval(id);
    };
  }, [viewerAuthed]);

  useEffect(() => {
    const media = window.matchMedia ? window.matchMedia('(max-width: 640px)') : null;
    if (!media) {
      return;
    }
    const handleChange = (event) => {
      setIsMobile(event.matches);
      if (!event.matches) {
        setIsImmersive(false);
      }
    };
    setIsMobile(media.matches);
    if (media.addEventListener) {
      media.addEventListener('change', handleChange);
      return () => media.removeEventListener('change', handleChange);
    }
    media.addListener(handleChange);
    return () => media.removeListener(handleChange);
  }, []);

  useEffect(() => {
    if (isImmersive) {
      document.body.style.overflow = 'hidden';
      return () => {
        document.body.style.overflow = '';
      };
    }
    document.body.style.overflow = '';
  }, [isImmersive]);

  useEffect(() => {
    const wrapper = playerWrapRef.current;
    if (!wrapper) {
      return;
    }
    const handleWheel = (event) => {
      if (event.ctrlKey) {
        event.preventDefault();
      }
    };
    wrapper.addEventListener('wheel', handleWheel, { passive: false });
    return () => {
      wrapper.removeEventListener('wheel', handleWheel);
    };
  }, []);

  const handlePlayerPlay = () => {
    const url = trimUrl(streamUrl);
    if (!url) {
      return;
    }
    if (viewerAuthed && !markedPlayRef.current) {
      markedPlayRef.current = true;
      fetch('/api/viewer/play', {
        method: 'POST',
        credentials: 'include'
      })
        .then((res) => {
          if (res.status === 401) {
            markViewerUnauthorized();
          }
        })
        .catch(() => { });
    }
    if (!hlsRef.current && (!videoRef.current || !videoRef.current.src)) {
      loadStream(url, true);
    }
  };

  const handlePlayerIntent = () => {
    if (!viewerAuthed || !streamMeta.live) {
      return;
    }
    const video = videoRef.current;
    const hasRecoverableProblem =
      !video ||
      !video.src ||
      Boolean(video.error) ||
      status === 'Error' ||
      (status === 'Idle' && (video?.currentTime || 0) < 0.5);
    if (hasRecoverableProblem) {
      requestStreamReload(true, 5000);
    }
  };

  const handlePlayerDoubleClick = () => {
    if (isMobile) {
      return;
    }
    const wrapper = playerWrapRef.current;
    if (!wrapper) {
      return;
    }
    if (document.fullscreenElement) {
      document.exitFullscreen().catch(() => { });
      return;
    }
    wrapper.requestFullscreen().catch(() => { });
  };

  const streamKey = config.defaultStreamKey || 'stream';

  const startNiceAuth = async () => {
    setNiceAttempted(true);
    setNiceError('');
    setNiceStatus('loading');
    // iOS/in-app browsers are unstable with NICE popup flow; use same-tab redirect there.
    const useSameTab = isMobile || isIosDevice() || isLikelyInAppBrowser();
    // Desktop browsers: open popup synchronously to preserve user gesture.
    let authPopup = null;
    if (!useSameTab) {
      try {
        authPopup = window.open(
          'about:blank',
          'niceAuth',
          'width=480,height=700,menubar=no,toolbar=no,location=no,status=no'
        );
      } catch (err) {
        authPopup = null;
      }
    }
    try {
      const key = (streamKey || 'stream').trim() || 'stream';
      const deviceId = getOrCreateViewerDeviceId();
      const response = await fetch('/api/nice/start', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ streamKey: key, deviceId })
      });
      const raw = await response.text();
      let data = null;
      try {
        data = raw ? JSON.parse(raw) : null;
      } catch (err) {
        data = null;
      }
      if (!response.ok) {
        const message = data?.error || data?.message || raw || '본인인증을 시작할 수 없습니다.';
        const friendly = message === 'partner_db_error'
          ? '회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.'
          : message;
        throw new Error(friendly);
      }
      const requestNo = data?.requestNo || '';
      const authUrl = safeExternalUrl(data?.authUrl);
      if (!requestNo || !authUrl) {
        throw new Error('nice_invalid_payload');
      }
      setNiceRequestNo(requestNo);
      setNiceStatus('pending');
      if (useSameTab) {
        window.location.href = authUrl;
      } else if (authPopup && !authPopup.closed) {
        authPopup.location.href = authUrl;
        authPopup.focus();
      } else {
        window.location.href = authUrl;
      }
    } catch (err) {
      if (authPopup && !authPopup.closed) {
        try {
          authPopup.close();
        } catch (closeErr) {
          // ignore
        }
      }
      setNiceStatus('failed');
      setNiceError(err instanceof Error ? err.message : '본인인증을 시작할 수 없습니다.');
    }
  };

  const loadMessages = async (reset = false) => {
    if (!viewerAuthed) {
      return;
    }
    if (!streamKey) {
      return;
    }
    const afterId = reset ? 0 : lastMessageIdRef.current;
    try {
      const response = await fetch(
        `/api/chat/messages?streamKey=${encodeURIComponent(streamKey)}&afterId=${afterId}&limit=100`
      );
      if (!response.ok) {
        if (response.status === 401) {
          markViewerUnauthorized();
        }
        return;
      }
      const data = await response.json();
      if (!Array.isArray(data) || data.length === 0) {
        return;
      }
      setMessages((prev) => (reset ? data : [...prev, ...data]));
      const last = data[data.length - 1];
      lastMessageIdRef.current = Math.max(lastMessageIdRef.current, last.id || 0);
    } catch (err) {
      // Silent fail to keep UI clean.
    }
  };

  useEffect(() => {
    lastMessageIdRef.current = 0;
    setMessages([]);
    if (!viewerAuthed) {
      return undefined;
    }
    loadMessages(true);
    const id = window.setInterval(() => loadMessages(false), 5000);
    return () => window.clearInterval(id);
  }, [streamKey, viewerAuthed]);

  useEffect(() => {
    if (messagesEndRef.current) {
      messagesEndRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [messages]);

  const handleSendMessage = async (event) => {
    event.preventDefault();
    const message = messageInput.trim();
    if (!message) {
      return;
    }
    setChatNotice('');
    try {
      const response = await fetch('/api/chat/messages', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          streamKey,
          message
        })
      });
      if (!response.ok) {
        if (response.status === 401) {
          markViewerUnauthorized();
        }
        let nextNotice = '메시지 전송에 실패했습니다.';
        try {
          const data = await response.json();
          if (response.status === 429 || data?.error === 'message_rate_limited') {
            const retryMs = Number(data?.retryAfterMs);
            const retrySec = Number.isFinite(retryMs) && retryMs > 0 ? Math.ceil(retryMs / 1000) : 10;
            nextNotice = `질문은 ${retrySec}초 후 다시 보낼 수 있습니다.`;
          } else if (data?.error === 'message_too_long') {
            nextNotice = '질문이 너무 깁니다. 1000자 이하로 입력해 주세요.';
          }
        } catch (ignored) {
          // keep default notice
        }
        setChatNotice(nextNotice);
        return;
      }
      setMessageInput('');
      setChatNotice('관리자 승인 후 채팅에 표시됩니다.');
    } catch (err) {
      setChatNotice('메시지 전송에 실패했습니다.');
    }
  };

  const handleAudioButtonClick = () => {
    setAudioNotice('');
    setIsAudioModalOpen(true);
  };

  const closeSpeechRequestModal = () => {
    setIsSpeechRequestModalOpen(false);
  };

  const openQuestionModal = () => {
    setIsQaOpen(true);
  };

  const closeQuestionModal = () => {
    setIsQaOpen(false);
  };

  const handleOpenQuestionFromSpeech = () => {
    closeSpeechRequestModal();
    openQuestionModal();
  };

  const handleOpenAudioFromSpeech = () => {
    closeSpeechRequestModal();
    handleAudioButtonClick();
  };

  const openVoteModal = (message) => {
    setVoteModalMessage(message || '');
    setIsVoteModalOpen(true);
  };

  const closeVoteModal = () => {
    setIsVoteModalOpen(false);
    setVoteModalMessage('');
  };

  const openVotePageWindow = ({ popup: existingPopup } = {}) => {
    const rawVoteUrl = selectedMeeting?.voteUrl || '';
    if (!rawVoteUrl) {
      openVoteModal('투표 URL이 설정되지 않았습니다. 관리자에게 문의해주세요.');
      return false;
    }
    const target = safeExternalUrl(rawVoteUrl);
    if (!target) {
      openVoteModal('투표 페이지 주소가 올바르지 않습니다. 관리자에게 문의해주세요.');
      return false;
    }
    let popup = existingPopup || null;
    if (!popup) {
      const popupFeatures = [
        'popup=yes',
        'width=560',
        'height=820',
        'left=120',
        'top=80',
        'resizable=yes',
        'scrollbars=yes',
        'toolbar=no',
        'menubar=no',
        'status=no'
      ].join(',');
      try {
        popup = window.open('about:blank', '_blank', popupFeatures);
      } catch (err) {
        popup = null;
      }
    }
    if (!popup) {
      openVoteModal('브라우저 팝업을 허용해 주세요. 안 되면 외부 브라우저로 열어주세요.');
      return false;
    }
    try {
      popup.opener = null;
      popup.location.href = target;
      if (typeof popup.focus === 'function') {
        popup.focus();
      }
    } catch (err) {
      // ignore
    }
    return true;
  };

  const closeVotePopup = (popup) => {
    if (!popup) {
      return;
    }
    try {
      popup.close();
    } catch (err) {
      // ignore
    }
  };

  const openAttendanceModal = () => {
    if (viewerAuthed) {
      const streamKey = (config.defaultStreamKey || 'stream').trim();
      if (streamKey) {
        fetch(`/api/attendance?streamKey=${encodeURIComponent(streamKey)}`, { cache: 'no-store' })
          .then((res) => {
            if (!res.ok) {
              if (res.status === 401) {
                markViewerUnauthorized();
              }
              return Promise.reject(res);
            }
            return res.json();
          })
          .then((data) => {
            setAttendanceSummary(mapAttendanceSummary(data));
            setAttendanceError('');
          })
          .catch(() => {
            setAttendanceError('출석 현황을 불러오지 못했습니다.');
          });
      }
    }
    setIsAttendanceModalOpen(true);
  };

  const closeAttendanceModal = () => {
    setIsAttendanceModalOpen(false);
  };

  const openMaterialModal = (item) => {
    setSelectedMaterial(item);
    setSelectedPdfPage(1);
    setSelectedPdfPageCount(null);
    setSelectedPdfMode('canvas');
    setSelectedPdfLoading(false);
    setSelectedPdfRendering(false);
    setSelectedPdfError('');
    setSelectedPdfDocVersion(0);
  };

  const closeMaterialModal = () => {
    setSelectedMaterial(null);
    setSelectedPdfPage(1);
    setSelectedPdfPageCount(null);
    setSelectedPdfMode('canvas');
    setSelectedPdfLoading(false);
    setSelectedPdfRendering(false);
    setSelectedPdfError('');
    setSelectedPdfDocVersion(0);
  };

  const goPrevPdfPage = () => {
    setSelectedPdfPage((prev) => Math.max(1, prev - 1));
  };

  const goNextPdfPage = () => {
    const docPageCount = Number(pdfDocumentRef.current?.numPages) || 0;
    const knownPageCount = docPageCount || Number(selectedPdfPageCount) || 0;
    if (knownPageCount > 0 && selectedPdfPage >= knownPageCount) {
      return;
    }
    setSelectedPdfPage((prev) => prev + 1);
  };

  const handleVoteClick = async () => {
    if (voteChecking) {
      return;
    }
    if (!voteOpen) {
      openVoteModal('투표 전 입니다.');
      return;
    }
    if (!selectedMeeting?.voteUrl) {
      openVoteModal('투표 URL이 설정되지 않았습니다. 관리자에게 문의해주세요.');
      return;
    }
    const popupFeatures = [
      'popup=yes',
      'width=560',
      'height=820',
      'left=120',
      'top=80',
      'resizable=yes',
      'scrollbars=yes',
      'toolbar=no',
      'menubar=no',
      'status=no'
    ].join(',');
    let preOpenedPopup = null;
    try {
      preOpenedPopup = window.open('about:blank', '_blank', popupFeatures);
    } catch (err) {
      preOpenedPopup = null;
    }
    if (!preOpenedPopup) {
      openVoteModal('브라우저 팝업을 허용해 주세요. 안 되면 외부 브라우저로 열어주세요.');
      return;
    }
    try {
      preOpenedPopup.opener = null;
    } catch (err) {
      // ignore
    }

    setVoteChecking(true);
    try {
      const response = await fetch(
        `/api/vote/onsite?streamKey=${encodeURIComponent(streamKey || 'stream')}`,
        { cache: 'no-store' }
      );
      if (response.status === 401) {
        closeVotePopup(preOpenedPopup);
        markViewerUnauthorized();
        openVoteModal('본인인증이 필요합니다.');
        return;
      }
      if (!response.ok) {
        closeVotePopup(preOpenedPopup);
        openVoteModal('현장투표 가능 여부를 확인할 수 없습니다.');
        return;
      }
      const data = await response.json().catch(() => null);
      if (data?.allowed) {
        const opened = openVotePageWindow({ popup: preOpenedPopup });
        if (!opened) {
          closeVotePopup(preOpenedPopup);
        }
        return;
      }
      closeVotePopup(preOpenedPopup);
      if (data?.reason === 'vote_not_open') {
        openVoteModal('투표 전 입니다.');
      } else if (data?.reason === 'paper_vote_already_used') {
        openVoteModal('이미 서면결의로 투표권을 행사하셨습니다.');
      } else if (data?.reason === 'mail_vote_already_used') {
        openVoteModal('이미 우편으로 투표권을 행사하셨습니다.');
      } else if (data?.reason === 'electronic_vote_already_used') {
        openVoteModal('이미 전자투표로 투표권을 행사하셨습니다.');
      } else {
        openVoteModal('현장투표가 불가능합니다.');
      }
    } catch (err) {
      closeVotePopup(preOpenedPopup);
      openVoteModal('현장투표 가능 여부를 확인할 수 없습니다.');
    } finally {
      setVoteChecking(false);
    }
  };

  const getAudioErrorMessage = (code) => {
    switch (code) {
      case 'streamKey_required':
        return '스트림 키가 없어 업로드할 수 없습니다.';
      case 'file_required':
        return '업로드할 파일이 없습니다.';
      case 'file_too_large':
        return '음성 파일은 100MB 이하만 업로드할 수 있습니다.';
      case 'audio_only':
        return '음성 파일만 업로드할 수 있습니다.';
      case 'ffmpeg_missing':
        return '서버에 음성 변환 도구가 없습니다. 관리자에게 문의하세요.';
      case 'transcode_failed':
        return '음성 변환에 실패했습니다. 다른 형식으로 다시 시도해주세요.';
      case 'duration_check_failed':
        return '음성 길이를 확인할 수 없습니다. 다른 파일로 시도해주세요.';
      case 'duration_exceeded':
        return '음성 길이는 최대 2분까지 가능합니다.';
      case 'process_interrupted':
        return '업로드 처리 중 문제가 발생했습니다. 다시 시도해주세요.';
      default:
        return '음성 업로드에 실패했습니다.';
    }
  };

  const readAudioErrorMessage = async (response) => {
    try {
      const data = await response.json();
      return getAudioErrorMessage(data?.error);
    } catch (err) {
      return '음성 업로드에 실패했습니다.';
    }
  };

  const showAudioToast = (message, durationMs = 3500) => {
    const text = (message || '').trim();
    if (!text) {
      return;
    }
    setAudioToast(text);
    if (audioToastTimerRef.current) {
      window.clearTimeout(audioToastTimerRef.current);
    }
    audioToastTimerRef.current = window.setTimeout(() => {
      setAudioToast('');
      audioToastTimerRef.current = null;
    }, Math.max(500, Number(durationMs) || 3500));
  };

  const handleAudioFileChange = async (event) => {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }
    event.target.value = '';
    const maxBytes = 100 * 1024 * 1024;
    if (file.size > maxBytes) {
      setAudioNotice('음성 파일은 100MB 이하만 업로드할 수 있습니다.');
      return;
    }
    if (!isSupportedAudioFile(file)) {
      setAudioNotice('음성 파일만 업로드할 수 있습니다.');
      return;
    }
    setAudioNotice('업로드 중입니다...');
    setAudioUploading(true);
    try {
      const formData = new FormData();
      formData.append('streamKey', streamKey);
      formData.append('file', file);
      formData.append('recorded', 'false');
      const response = await fetch('/api/chat/audio', {
        method: 'POST',
        body: formData
      });
      if (!response.ok) {
        const message = await readAudioErrorMessage(response);
        setAudioNotice(message);
        return;
      }
      setAudioNotice('전송이 완료되었습니다.');
      showAudioToast('전송이 완료되었습니다.');
      closeAudioModal();
    } catch (err) {
      setAudioNotice('음성 업로드에 실패했습니다.');
    } finally {
      setAudioUploading(false);
    }
  };

  const closeAudioModal = () => {
    setIsAudioModalOpen(false);
    if (recordState === 'recording') {
      stopRecording();
    }
    setRecordState('idle');
    setRecordSeconds(0);
    setRecordBlob(null);
    if (recordPreviewUrl) {
      URL.revokeObjectURL(recordPreviewUrl);
    }
    setRecordPreviewUrl('');
    recordChunksRef.current = [];
  };

  const startRecording = async () => {
    if (!navigator.mediaDevices?.getUserMedia) {
      setAudioNotice('이 브라우저는 녹음을 지원하지 않습니다.');
      return;
    }
    setAudioNotice('');
    try {
      if (recordPreviewAudioRef.current) {
        try {
          recordPreviewAudioRef.current.pause();
          recordPreviewAudioRef.current.currentTime = 0;
        } catch (err) { }
      }
      if (recordPreviewUrl) {
        URL.revokeObjectURL(recordPreviewUrl);
      }
      setRecordPreviewUrl('');
      setRecordBlob(null);
      setRecordPreviewDurationSec(null);

      const video = videoRef.current;
      if (video) {
        videoAudioStateRef.current = { muted: video.muted, volume: video.volume };
        video.muted = true;
        video.defaultMuted = true;
        video.volume = 0;
      }

      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: { ideal: true },
          noiseSuppression: { ideal: true },
          // AGC can pump background noise; keep it off by default.
          autoGainControl: { ideal: false },
          channelCount: 1,
          sampleRate: 48000,
          sampleSize: 16
        }
      });
      mediaStreamRef.current = stream;
      const preferredTypes = [
        // Prefer mp4/aac when available for best mobile (iOS) playback compatibility.
        'audio/mp4;codecs=mp4a.40.2',
        'audio/mp4',
        'audio/aac',
        'audio/webm;codecs=opus',
        'audio/webm',
        'audio/ogg;codecs=opus',
        'audio/ogg'
      ];
      const mimeType = preferredTypes.find((type) => window.MediaRecorder?.isTypeSupported?.(type));
      const recorder = new MediaRecorder(
        stream,
        mimeType ? { mimeType, audioBitsPerSecond: AUDIO_BITRATE } : { audioBitsPerSecond: AUDIO_BITRATE }
      );
      mediaRecorderRef.current = recorder;
      recordChunksRef.current = [];
      recorder.ondataavailable = (event) => {
        if (event.data && event.data.size > 0) {
          recordChunksRef.current.push(event.data);
        }
      };
      recorder.onstop = () => {
        const chunks = recordChunksRef.current;
        const rawType = recorder.mimeType || mimeType || 'audio/webm';
        const blobType = rawType.split(';')[0];
        const blob = new Blob(chunks, { type: blobType });
        if (!blob || blob.size <= 0) {
          setRecordBlob(null);
          setRecordPreviewUrl('');
          setRecordPreviewDurationSec(null);
          setRecordState('idle');
          setAudioNotice('녹음 데이터가 비어 있습니다. 다시 녹음해주세요.');
          recordChunksRef.current = [];
          if (mediaStreamRef.current) {
            mediaStreamRef.current.getTracks().forEach((track) => track.stop());
            mediaStreamRef.current = null;
          }
          const nextVideo = videoRef.current;
          if (nextVideo) {
            nextVideo.muted = videoAudioStateRef.current.muted;
            nextVideo.volume = videoAudioStateRef.current.volume;
          }
          return;
        }
        setRecordBlob(blob);
        setRecordPreviewDurationSec(null);
        if (recordPreviewUrl) {
          URL.revokeObjectURL(recordPreviewUrl);
        }
        setRecordPreviewUrl(URL.createObjectURL(blob));
        setRecordState('ready');
        recordChunksRef.current = [];
        if (mediaStreamRef.current) {
          mediaStreamRef.current.getTracks().forEach((track) => track.stop());
          mediaStreamRef.current = null;
        }

        const nextVideo = videoRef.current;
        if (nextVideo) {
          nextVideo.muted = videoAudioStateRef.current.muted;
          nextVideo.volume = videoAudioStateRef.current.volume;
        }
      };
      recorder.start(1000);
      setRecordState('recording');
      setRecordSeconds(0);
      recordTimerRef.current = window.setInterval(() => {
        setRecordSeconds((prev) => {
          const next = prev + 1;
          if (next >= MAX_RECORD_SECONDS) {
            stopRecording();
            setAudioNotice('녹음은 최대 2분까지 가능합니다.');
            return MAX_RECORD_SECONDS;
          }
          return next;
        });
      }, 1000);
    } catch (err) {
      const video = videoRef.current;
      if (video) {
        video.muted = videoAudioStateRef.current.muted;
        video.volume = videoAudioStateRef.current.volume;
      }
      setAudioNotice('마이크 권한이 필요합니다.');
    }
  };

  const stopRecording = () => {
    if (recordTimerRef.current) {
      window.clearInterval(recordTimerRef.current);
      recordTimerRef.current = null;
    }
    if (mediaRecorderRef.current && mediaRecorderRef.current.state !== 'inactive') {
      try {
        mediaRecorderRef.current.requestData();
      } catch (err) {
        // ignore unsupported/invalid state
      }
      mediaRecorderRef.current.stop();
    }
    // If onstop doesn't fire (edge cases), restore playback audio shortly after.
    window.setTimeout(() => {
      const video = videoRef.current;
      const recorder = mediaRecorderRef.current;
      if (video && (!recorder || recorder.state === 'inactive')) {
        video.muted = videoAudioStateRef.current.muted;
        video.volume = videoAudioStateRef.current.volume;
      }
    }, 200);
    setRecordState('stopping');
  };

  const uploadRecorded = async () => {
    if (!recordBlob) {
      return;
    }
    if (recordBlob.size <= 0) {
      setAudioNotice('녹음 데이터가 비어 있습니다. 다시 녹음해주세요.');
      return;
    }
    setAudioUploading(true);
    setAudioNotice('업로드 중입니다...');
    try {
      const type = recordBlob.type || '';
      let extension = 'm4a';
      if (type.includes('ogg')) {
        extension = 'ogg';
      } else if (type.includes('webm')) {
        extension = 'webm';
      } else if (type.includes('mp4') || type.includes('aac')) {
        extension = 'm4a';
      }
      const file = new File([recordBlob], `recording-${Date.now()}.${extension}`, {
        type: type || 'application/octet-stream'
      });
      const formData = new FormData();
      formData.append('streamKey', streamKey);
      formData.append('file', file);
      formData.append('recorded', 'true');
      const response = await fetch('/api/chat/audio', {
        method: 'POST',
        body: formData
      });
      if (!response.ok) {
        const message = await readAudioErrorMessage(response);
        setAudioNotice(message.replace('음성', '녹음'));
        return;
      }
      setAudioNotice('전송이 완료되었습니다.');
      showAudioToast('전송이 완료되었습니다.');
      closeAudioModal();
    } catch (err) {
      setAudioNotice('녹음 업로드에 실패했습니다.');
    } finally {
      setAudioUploading(false);
    }
  };

  useEffect(() => {
    return () => {
      if (audioToastTimerRef.current) {
        window.clearTimeout(audioToastTimerRef.current);
        audioToastTimerRef.current = null;
      }
    };
  }, []);

  useEffect(() => {
    return () => {
      if (recordTimerRef.current) {
        window.clearInterval(recordTimerRef.current);
      }
      if (mediaRecorderRef.current && mediaRecorderRef.current.state !== 'inactive') {
        mediaRecorderRef.current.stop();
      }
      if (mediaStreamRef.current) {
        mediaStreamRef.current.getTracks().forEach((track) => track.stop());
      }
      if (recordPreviewUrl) {
        URL.revokeObjectURL(recordPreviewUrl);
      }
    };
  }, [recordPreviewUrl]);

  const headline = config.baseUrl ? 'Live Feed' : 'Live Viewer';
  const titleText = streamMeta.title || '라이브 방송';
  const statusDisplay = useMemo(() => {
    if (streamMeta.live) {
      return '라이브중';
    }
    if (typeof streamMeta.minutesToStart === 'number') {
      const minutes = Math.max(0, streamMeta.minutesToStart);
      if (minutes >= 720) {
        return '준비중';
      }
      if (minutes >= 60) {
        return `시작 ${Math.max(1, Math.floor(minutes / 60))}시간 전`;
      }
      if (minutes > 0) {
        return `시작 ${minutes}분 전`;
      }
    }
    return streamMeta.statusLabel || '';
  }, [streamMeta.live, streamMeta.minutesToStart, streamMeta.statusLabel]);
  const showViewers = typeof viewerCount === 'number' && viewerCount > 0;
  const viewerText = useMemo(() => {
    const value = typeof viewerCount === 'number' ? viewerCount : 0;
    const padded = String(Math.max(0, value)).padStart(2, '0');
    return `${padded}명 시청 중`;
  }, [viewerCount]);

  if (!viewerAuthChecked && !viewerAuthed) {
    return (
      <div className="relative min-h-screen overflow-hidden bg-[#f7f4ef] text-slate-900">
        <div className="pointer-events-none absolute inset-0">
          <div className="absolute -left-40 top-16 h-80 w-80 rounded-full bg-amber-200/40 blur-3xl" />
          <div className="absolute right-0 top-0 h-[26rem] w-[26rem] rounded-full bg-sky-200/40 lg:blur-[120px]" />
          <div className="absolute bottom-0 left-1/3 h-72 w-72 rounded-full bg-emerald-200/30 lg:blur-[120px]" />
        </div>
        <div className="relative z-10 mx-auto flex min-h-screen w-full max-w-md items-center px-6">
          <div className="w-full rounded-3xl border border-white/60 bg-white/90 p-8 shadow-2xl ring-1 ring-black/5">
            <p className="text-[10px] uppercase tracking-[0.3em] text-slate-500">Loading</p>
            <h2 className="mt-2 text-2xl font-semibold text-slate-900">인증 상태 확인 중</h2>
            <p className="mt-2 text-sm text-slate-500">잠시만 기다려 주세요.</p>
            <div className="mt-6 flex items-center gap-3">
              <div className="h-4 w-4 animate-spin rounded-full border-2 border-slate-200 border-t-emerald-500" />
              <span className="text-sm text-slate-600">Checking session...</span>
            </div>
          </div>
        </div>
      </div>
    );
  }

  const showNiceGate = !viewerAuthed;

  if (showNiceGate) {
    const statusText =
      !niceAttempted
        ? '미인증'
        : niceStatus === 'just-verified'
          ? '인증 성공'
          : niceStatus === 'failed'
            ? '인증 실패'
            : niceStatus === 'pending'
              ? '인증 진행 중'
              : '미인증';
    return (
      <div className="relative min-h-screen overflow-hidden bg-[#f7f4ef] text-slate-900">
        <div className="pointer-events-none absolute inset-0">
          <div className="absolute -left-40 top-16 h-80 w-80 rounded-full bg-amber-200/40 blur-3xl" />
          <div className="absolute right-0 top-0 h-[26rem] w-[26rem] rounded-full bg-sky-200/40 lg:blur-[120px]" />
          <div className="absolute bottom-0 left-1/3 h-72 w-72 rounded-full bg-emerald-200/30 lg:blur-[120px]" />
        </div>
        <div className="relative z-10 mx-auto flex min-h-screen w-full max-w-md items-center px-6">
          <div className="w-full rounded-3xl border border-white/60 bg-white/90 p-8 shadow-2xl ring-1 ring-black/5">
            <p className="text-[10px] uppercase tracking-[0.3em] text-slate-500">본인인증</p>
            <h2 className="mt-2 text-2xl font-semibold text-slate-900">본인인증이 필요합니다</h2>
            <p className="mt-2 text-sm text-slate-500">
              서비스 이용을 위해 본인인증을 진행해 주세요.
            </p>
            <p className="mt-3 text-sm font-semibold text-slate-800">{statusText}</p>
            <div className="mt-6 space-y-2">
              <button
                type="button"
                onClick={startNiceAuth}
                disabled={niceStatus === 'loading'}
                className="w-full rounded-2xl bg-slate-900 px-4 py-3 text-sm font-semibold text-white hover:bg-slate-800 disabled:opacity-60"
              >
                {niceStatus === 'loading'
                  ? '인증 시작 중...'
                  : niceStatus === 'pending'
                    ? '인증 이어하기'
                    : '본인인증 시작'}
              </button>
              {niceStatus === 'pending' ? (
                <p className="text-xs text-slate-500">
                  팝업이 열리지 않으면 팝업 차단을 해제해 주세요.
                </p>
              ) : null}
              {niceStatus === 'just-verified' ? (
                <p className="text-xs text-emerald-600">인증이 완료되었습니다. 잠시 후 이동됩니다.</p>
              ) : null}
              {niceAttempted && niceError ? (
                <p className="text-xs text-rose-500">{niceError}</p>
              ) : null}
            </div>
          </div>
        </div>
      </div>
    );
  }

  // --- Post-NICE meeting flow gates ---

  if (viewerPageState === 'loading-meetings' || viewerPageState === 'initializing') {
    return (
      <div className="relative min-h-screen overflow-hidden bg-[#f7f4ef] text-slate-900">
        <div className="pointer-events-none absolute inset-0">
          <div className="absolute -left-40 top-16 h-80 w-80 rounded-full bg-amber-200/40 blur-3xl" />
          <div className="absolute right-0 top-0 h-[26rem] w-[26rem] rounded-full bg-sky-200/40 lg:blur-[120px]" />
        </div>
        <div className="relative z-10 mx-auto flex min-h-screen w-full max-w-md items-center px-6">
          <div className="w-full rounded-3xl border border-white/60 bg-white/95 p-8 shadow-2xl ring-1 ring-black/5">
            <p className="text-[10px] uppercase tracking-[0.3em] text-slate-500">Loading</p>
            <h2 className="mt-2 text-2xl font-semibold text-slate-900">참여 회의 확인 중</h2>
            <p className="mt-2 text-sm text-slate-500">잠시만 기다려 주세요.</p>
            <div className="mt-6 flex items-center gap-3">
              <div className="h-4 w-4 animate-spin rounded-full border-2 border-slate-200 border-t-emerald-500" />
              <span className="text-sm text-slate-600">Loading meetings...</span>
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (viewerPageState === 'not-in-roster') {
    return (
      <div className="relative min-h-screen overflow-hidden bg-[#f7f4ef] text-slate-900">
        <div className="pointer-events-none absolute inset-0">
          <div className="absolute -left-40 top-16 h-80 w-80 rounded-full bg-amber-200/40 blur-3xl" />
        </div>
        <div className="relative z-10 mx-auto flex min-h-screen w-full max-w-md items-center px-6">
          <div className="w-full rounded-3xl border border-white/60 bg-white/95 p-8 shadow-2xl ring-1 ring-black/5">
            <p className="text-[10px] uppercase tracking-[0.3em] text-rose-500">접속 불가</p>
            <h2 className="mt-2 text-2xl font-semibold text-slate-900">명부에 등록된 회의가 없습니다.</h2>
            <p className="mt-3 text-sm text-slate-500">
              {meetingFlowError ||
                '본인인증 정보로 참여 가능한 회의가 확인되지 않습니다. 관리자에게 문의해 주세요.'}
            </p>
            <button
              type="button"
              onClick={handleBackToAuth}
              className="mt-6 w-full rounded-2xl border border-slate-300 bg-white px-4 py-3 text-sm font-semibold text-slate-700 transition hover:border-slate-400 hover:bg-slate-50"
            >
              ← 뒤로가기 (다시 본인인증)
            </button>
          </div>
        </div>
      </div>
    );
  }

  if (viewerPageState === 'access-closed') {
    return (
      <div className="relative min-h-screen overflow-hidden bg-[#f7f4ef] text-slate-900">
        <div className="pointer-events-none absolute inset-0">
          <div className="absolute -left-40 top-16 h-80 w-80 rounded-full bg-amber-200/40 blur-3xl" />
        </div>
        <div className="relative z-10 mx-auto flex min-h-screen w-full max-w-md items-center px-6">
          <div className="w-full rounded-3xl border border-white/60 bg-white/95 p-8 shadow-2xl ring-1 ring-black/5">
            <p className="text-[10px] uppercase tracking-[0.3em] text-rose-500">접속 차단</p>
            <h2 className="mt-2 text-2xl font-semibold text-slate-900">접속 가능한 시간이 아닙니다.</h2>
            <p className="mt-3 text-sm text-slate-500">
              {meetingFlowError ||
                '회의의 접속 허용 시간이 아닙니다. 관리자에게 문의해 주세요.'}
            </p>
            <button
              type="button"
              onClick={handleBackToAuth}
              className="mt-6 w-full rounded-2xl border border-slate-300 bg-white px-4 py-3 text-sm font-semibold text-slate-700 transition hover:border-slate-400 hover:bg-slate-50"
            >
              ← 뒤로가기 (다시 본인인증)
            </button>
          </div>
        </div>
      </div>
    );
  }

  if (viewerPageState === 'selecting-meeting') {
    return (
      <ViewerMeetingSelector
        meetings={myMeetings}
        onSelect={enterMeeting}
      />
    );
  }

  if (viewerPageState === 'exited') {
    return (
      <div className="relative min-h-screen overflow-hidden bg-[#f7f4ef] text-slate-900">
        <div className="pointer-events-none absolute inset-0">
          <div className="absolute -left-40 top-16 h-80 w-80 rounded-full bg-amber-200/40 blur-3xl" />
        </div>
        <div className="relative z-10 mx-auto flex min-h-screen w-full max-w-md items-center px-6">
          <div className="w-full rounded-3xl border border-white/60 bg-white/95 p-8 shadow-2xl ring-1 ring-black/5 text-center">
            <p className="text-[10px] uppercase tracking-[0.3em] text-emerald-500">EXIT</p>
            <h2 className="mt-2 text-2xl font-semibold text-slate-900">퇴장 완료.</h2>
            <p className="mt-2 text-sm text-slate-500">퇴장시간 {exitTimeText}</p>
            <p className="mt-4 text-xs text-slate-400">잠시 후 메인 페이지로 이동합니다...</p>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="relative min-h-screen overflow-hidden bg-[#f7f4ef] text-slate-900">
      <div className="pointer-events-none absolute inset-0">
        <div className="absolute -left-40 top-16 h-80 w-80 rounded-full bg-amber-200/40 blur-3xl" />
        <div className="absolute right-0 top-0 h-[26rem] w-[26rem] rounded-full bg-sky-200/40 lg:blur-[120px]" />
        <div className="absolute bottom-0 left-1/3 h-72 w-72 rounded-full bg-emerald-200/30 lg:blur-[120px]" />
      </div>

      {/* Floating 퇴장 button — always visible in the viewer UI. */}
      <button
        type="button"
        onClick={handleViewerExit}
        disabled={exitInFlight}
        className="fixed bottom-5 right-5 z-50 rounded-full bg-slate-900 px-5 py-3 text-sm font-semibold text-white shadow-lg transition hover:bg-slate-800 disabled:opacity-60 sm:bottom-8 sm:right-8"
      >
        {exitInFlight ? '퇴장 중...' : '퇴장'}
      </button>

      <div className="relative z-10 mx-auto flex w-full max-w-md flex-col gap-6 px-0 pt-2 pb-4 sm:max-w-4xl sm:gap-10 sm:px-6 sm:py-12">
        <main className="grid gap-6">
          <section className="rise-in flex flex-col gap-3 rounded-none border-y border-white/60 bg-white/90 p-3 shadow-2xl ring-1 ring-black/5 sm:rounded-3xl sm:border sm:gap-4 sm:p-6">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex flex-wrap items-center gap-2 text-sm text-slate-600">
                <span
                  className={`rounded-full px-2 py-0.5 text-[9px] font-semibold uppercase tracking-[0.2em] ${streamMeta.live
                    ? 'border border-rose-500 bg-rose-500 text-white'
                    : 'border border-slate-200 bg-white text-slate-600'
                    }`}
                >
                  Live
                </span>
                <span className="rounded-full border border-emerald-200 bg-emerald-50 px-2 py-0.5 text-[9px] font-semibold uppercase tracking-[0.2em] text-emerald-700">
                  본인인증 완료
                </span>
                {statusDisplay ? <span className="text-xs text-slate-500 sm:text-sm">{statusDisplay}</span> : null}
                {voteOpen ? (
                  <button
                    type="button"
                    onClick={handleVoteClick}
                    disabled={voteChecking}
                    className="rounded-full border border-amber-600 bg-amber-500 px-4 py-1 text-sm font-semibold text-white shadow-sm hover:bg-amber-600 disabled:opacity-70"
                  >
                    {voteChecking ? '확인중...' : '투표시작'}
                  </button>
                ) : null}
              </div>
            </div>
            <div
              ref={playerWrapRef}
              className={`player-wrap group relative -mx-3 h-[320px] overflow-hidden rounded-none border-y border-slate-100 bg-black sm:mx-0 sm:h-[360px] sm:rounded-2xl sm:border md:h-[520px] ${isImmersive ? 'is-immersive' : ''}`}
            >
              {niceToast || audioToast ? (
                <div className="pointer-events-none absolute left-1/2 top-3 z-20 -translate-x-1/2 space-y-2">
                  {niceToast ? (
                    <div className="rounded-full border border-emerald-200 bg-white/95 px-4 py-2 text-xs font-semibold text-emerald-700 shadow-sm">
                      {niceToast}
                    </div>
                  ) : null}
                  {audioToast ? (
                    <div className="rounded-full border border-slate-200 bg-white/95 px-4 py-2 text-xs font-semibold text-slate-800 shadow-sm">
                      {audioToast}
                    </div>
                  ) : null}
                </div>
              ) : null}
              <video
                ref={videoRef}
                className="player-video h-full w-full bg-black object-cover"
                controls
                playsInline
                onPlay={handlePlayerPlay}
                onClick={handlePlayerIntent}
                onTouchStart={handlePlayerIntent}
                onDoubleClick={handlePlayerDoubleClick}
              />
            </div>
            <div className="mt-2">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <div className="text-base font-semibold text-slate-900 sm:text-lg">{titleText}</div>
                  <div className="mt-1 text-xs text-slate-500">{viewerText}</div>
                </div>
              </div>
            </div>
            <div className="mt-1 grid gap-2">
              <div className="flex items-center gap-2">
                {attendanceVisible ? (
                  <button
                    type="button"
                    onClick={openAttendanceModal}
                    className="flex-1 rounded-2xl border border-slate-200 bg-white px-3 py-3 text-sm font-semibold text-slate-700 hover:border-slate-300"
                  >
                    참석자 현황
                  </button>
                ) : (
                  <button
                    type="button"
                    onClick={() => setIsMaterialsPanelOpen((prev) => !prev)}
                    className="flex-1 rounded-2xl border border-slate-200 bg-white px-3 py-3 text-sm font-semibold text-slate-700 hover:border-slate-300"
                  >
                    회의자료
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => setIsSpeechRequestModalOpen(true)}
                  className="flex-1 rounded-2xl border border-slate-200 bg-white px-3 py-3 text-sm font-semibold text-slate-700 hover:border-slate-300"
                >
                  발언요청
                </button>
                <button
                  type="button"
                  onClick={handleVoteClick}
                  disabled={voteChecking}
                  className={`flex-1 rounded-2xl px-3 py-3 text-sm font-semibold shadow-sm ${
                    voteOpen
                      ? 'bg-emerald-500 text-white hover:bg-emerald-600'
                      : 'border border-slate-200 bg-white text-slate-700 hover:border-slate-300'
                  }`}
                >
                  {voteChecking ? '확인중...' : voteOpen ? '투표시작' : '투표하기'}
                </button>
              </div>
              {isMaterialsPanelOpen ? (
                <div className="rounded-2xl border border-slate-100 bg-white p-3 shadow-sm">
                  <div className="flex items-center justify-between text-xs font-semibold uppercase tracking-[0.9em] text-slate-500">
                    <span>자료</span>
                    <span className="text-slate-400">{materials.length}개</span>
                  </div>
                  <div className="mt-2 max-h-48 space-y-2 overflow-y-auto pr-1">
                    {materialsError ? (
                      <div className="rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-600">
                        {materialsError}
                      </div>
                    ) : null}
                    {materialsLoading && materials.length === 0 ? (
                      <div className="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-500">
                        자료를 불러오는 중입니다.
                      </div>
                    ) : null}
                    {!materialsLoading && !materialsError && materials.length === 0 ? (
                      <div className="rounded-xl border border-dashed border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-500">
                        등록된 자료가 없습니다.
                      </div>
                    ) : null}
                    {materials.map((item) => (
                      <div
                        key={item.id}
                        className="flex items-start justify-between gap-3 rounded-xl border border-slate-100 bg-slate-50 px-3 py-2"
                      >
                        <div className="min-w-0">
                          <button
                            type="button"
                            onClick={() => openMaterialModal(item)}
                            className="text-left text-sm font-semibold text-slate-800 hover:text-slate-900"
                          >
                            {item.title || '자료'}
                          </button>
                          <p className="mt-1 text-xs text-slate-500">
                            {item.type === 'text' ? '텍스트 문서' : 'PDF 문서'}
                          </p>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              ) : null}
              <div className="rounded-2xl border border-slate-100 bg-white p-3 shadow-sm">
                <div className="flex items-center justify-between text-xs font-semibold uppercase tracking-[0.3em] text-slate-500">
                  <span>질의 목록</span>
                  <span className="text-slate-400">{messages.length}개</span>
                </div>
                <div className="mt-2 max-h-48 space-y-2 overflow-y-auto pr-1">
                  {messages.length === 0 ? (
                    <div className="rounded-xl border border-dashed border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-500">
                      승인된 질문이 아직 없습니다.
                    </div>
                  ) : null}
                  {messages.map((item) => (
                    <div
                      key={item.id}
                      className="rounded-xl border border-slate-100 bg-slate-50 px-3 py-2"
                    >
                      <div className="flex flex-wrap items-center gap-2 text-[10px] text-slate-500">
                        <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-600">
                          {getMessageDisplayName(item)}
                        </span>
                        <span className="text-[10px] text-slate-400">
                          {formatMessageTime(item?.createdAtEpochMs || item?.createdAt)}
                        </span>
                      </div>
                      <p className="mt-1 text-sm text-slate-800">{item.message}</p>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </section>
        </main>
      </div>

      <input
        ref={audioInputRef}
        type="file"
        accept="audio/*"
        onChange={handleAudioFileChange}
        className="hidden"
      />

      {isVoteModalOpen ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 px-4">
          <div className="absolute inset-0" onClick={closeVoteModal} aria-hidden="true" />
          <div className="relative z-10 w-full max-w-sm rounded-3xl border border-slate-200 bg-white p-6 shadow-xl">
            <div className="flex items-center justify-between">
              <h3 className="text-lg font-semibold text-slate-900">투표</h3>
              <button
                type="button"
                onClick={closeVoteModal}
                className="rounded-full border border-slate-200 px-3 py-1 text-xs text-slate-600 hover:border-slate-300"
              >
                닫기
              </button>
            </div>
            <p className="mt-4 text-sm text-slate-700">
              {voteModalMessage || '현장투표가 불가능합니다.'}
            </p>
            <button
              type="button"
              onClick={closeVoteModal}
              className="mt-6 w-full rounded-2xl bg-slate-900 px-4 py-3 text-sm font-semibold text-white hover:bg-slate-800"
            >
              확인
            </button>
          </div>
        </div>
      ) : null}

      {isAttendanceModalOpen ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 px-4">
          <div className="absolute inset-0" onClick={closeAttendanceModal} aria-hidden="true" />
          <div className="relative z-10 w-full max-w-sm rounded-3xl border border-slate-200 bg-white p-6 shadow-xl">
            <div className="flex items-center justify-between">
              <h3 className="text-lg font-semibold text-slate-900">출석 현황</h3>
              <button
                type="button"
                onClick={closeAttendanceModal}
                className="rounded-full border border-slate-200 px-3 py-1 text-xs text-slate-600 hover:border-slate-300"
              >
                닫기
              </button>
            </div>
            <div className="mt-4 grid gap-2 text-sm text-slate-700">
              <div className="grid grid-cols-[1fr_64px_64px] items-center gap-2 border-b border-slate-100 pb-2 text-xs font-normal">
                <span className="text-slate-400">구분</span>
                <span className="text-center text-slate-600">참석</span>
                <span className="text-center text-slate-600">제출</span>
              </div>
              <div className="grid grid-cols-[1fr_64px_64px] items-center gap-2">
                <span>서면결의</span>
                <span className="text-center">{attendanceSummary.paper}</span>
                <span className="text-center text-slate-600">{attendanceSummary.paperSubmitted}</span>
              </div>
              <div className="grid grid-cols-[1fr_64px_64px] items-center gap-2">
                <span>우편 제출</span>
                <span className="text-center">{attendanceSummary.mail}</span>
                <span className="text-center text-slate-600">{attendanceSummary.mailSubmitted}</span>
              </div>
              <div className="grid grid-cols-[1fr_64px_64px] items-center gap-2">
                <span>전자투표</span>
                <span className="text-center">{attendanceSummary.electronic}</span>
                <span className="text-center text-slate-600">{attendanceSummary.electronicSubmitted}</span>
              </div>
              <div className="grid grid-cols-[1fr_64px_64px] items-center gap-2">
                <span>온라인 참석</span>
                <span className="text-center">{attendanceSummary.online}</span>
                <span className="text-center text-slate-400">-</span>
              </div>
              <div className="grid grid-cols-[1fr_64px_64px] items-center gap-2">
                <span>현장 참석(오프라인)</span>
                <span className="text-center">{attendanceSummary.fieldOnsite}</span>
                <span className="text-center text-slate-400">-</span>
              </div>
              <div className="grid grid-cols-[1fr_64px_64px] items-center gap-2 text-amber-600">
                <span>전체 조합원</span>
                <span className="text-center">{attendanceSummary.attendingTotal}</span>
                <span className="text-center">{attendanceSummary.total}</span>
              </div>
            </div>
            {attendanceError ? (
              <p className="mt-4 text-xs text-rose-600">{attendanceError}</p>
            ) : null}
          </div>
        </div>
      ) : null}

      {isSpeechRequestModalOpen ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 px-4">
          <div className="absolute inset-0" onClick={closeSpeechRequestModal} aria-hidden="true" />
          <div className="relative z-10 w-full max-w-sm rounded-3xl border border-slate-200 bg-white p-6 shadow-xl">
            <div className="flex items-center justify-between">
              <h3 className="text-lg font-semibold text-slate-900">발언요청</h3>
              <button
                type="button"
                onClick={closeSpeechRequestModal}
                className="rounded-full border border-slate-200 px-3 py-1 text-xs text-slate-600 hover:border-slate-300"
              >
                닫기
              </button>
            </div>
            <div className="mt-6 grid gap-3">
              <button
                type="button"
                onClick={handleOpenQuestionFromSpeech}
                className="rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm font-semibold text-slate-700 hover:border-slate-300"
              >
                질의(문자)
              </button>
              <button
                type="button"
                onClick={handleOpenAudioFromSpeech}
                className="rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm font-semibold text-slate-700 hover:border-slate-300"
              >
                질의(음성)
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {isQaOpen ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 px-4">
          <div className="absolute inset-0" onClick={closeQuestionModal} aria-hidden="true" />
          <div className="relative z-10 w-full max-w-xl rounded-3xl border border-slate-200 bg-white p-4 shadow-xl">
            <div className="mb-3 flex justify-end">
              <button
                type="button"
                onClick={closeQuestionModal}
                className="rounded-full border border-slate-200 px-3 py-1 text-xs text-slate-600 hover:border-slate-300"
              >
                닫기
              </button>
            </div>
            <MobileQuestionPanel
              messages={messages}
              messageInput={messageInput}
              chatNotice={chatNotice}
              onMessageInputChange={(event) => setMessageInput(event.target.value)}
              onSendMessage={handleSendMessage}
              messagesEndRef={messagesEndRef}
            />
          </div>
        </div>
      ) : null}

      {isAudioModalOpen ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 px-4">
          <div className="w-full max-w-sm rounded-3xl border border-slate-200 bg-white p-6 shadow-xl">
            <div className="flex items-center justify-between">
              <h3 className="text-lg font-semibold text-slate-900">음성 전송</h3>
              <button
                type="button"
                onClick={closeAudioModal}
                className="rounded-full border border-slate-200 px-3 py-1 text-xs text-slate-600 hover:border-slate-300"
              >
                닫기
              </button>
            </div>

            {recordState === 'idle' ? (
              <div className="mt-6 grid gap-3">
                <button
                  type="button"
                  onClick={() => {
                    if (audioInputRef.current) {
                      audioInputRef.current.click();
                    }
                  }}
                  disabled={audioUploading}
                  className="rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-700 hover:border-slate-300 disabled:opacity-60"
                >
                  {audioUploading ? '업로드 중...' : '음성파일 전송'}
                </button>
                <button
                  type="button"
                  onClick={startRecording}
                  disabled={audioUploading}
                  className="rounded-2xl bg-amber-500 px-4 py-3 text-sm font-semibold text-white hover:bg-amber-400 disabled:opacity-60"
                >
                  녹음하기
                </button>
              </div>
            ) : null}

            {recordState === 'recording' ? (
              <div className="mt-6 grid gap-3">
                <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-700">
                  녹음 중 {String(Math.floor(recordSeconds / 60)).padStart(2, '0')}:
                  {String(recordSeconds % 60).padStart(2, '0')}
                </div>
                <p className="text-xs text-slate-500">
                  녹음 중에는 방송 소리가 자동으로 음소거됩니다. (잡음/하울링 방지)
                </p>
                <button
                  type="button"
                  onClick={stopRecording}
                  className="rounded-2xl border border-rose-300 px-4 py-3 text-sm text-rose-600 hover:border-rose-400"
                >
                  녹음 중지
                </button>
              </div>
            ) : null}

            {recordState === 'ready' ? (
              <div className="mt-6 grid gap-3">
                <p className="text-xs text-slate-500">
                  녹음 길이 {formatClock(recordPreviewDurationSec ?? recordSeconds)}
                </p>
                {recordPreviewUrl ? (
                  <audio
                    key={recordPreviewUrl}
                    ref={recordPreviewAudioRef}
                    controls
                    preload="metadata"
                    className="w-full"
                    onLoadedMetadata={(event) => {
                      const duration = event.currentTarget?.duration;
                      if (typeof duration === 'number' && Number.isFinite(duration) && duration > 0) {
                        setRecordPreviewDurationSec(duration);
                      }
                    }}
                    onError={() => {
                      setAudioNotice('이 기기에서는 녹음 미리듣기가 지원되지 않을 수 있습니다. 전송은 가능합니다.');
                    }}
                  >
                    <source src={recordPreviewUrl} type={recordBlob?.type || undefined} />
                  </audio>
                ) : null}
                <button
                  type="button"
                  onClick={uploadRecorded}
                  disabled={audioUploading}
                  className="rounded-2xl bg-emerald-500 px-4 py-3 text-sm font-semibold text-white disabled:opacity-60"
                >
                  녹음 전송
                </button>
                <button
                  type="button"
                  onClick={startRecording}
                  className="rounded-2xl border border-slate-200 px-4 py-3 text-sm text-slate-700 hover:border-slate-300"
                >
                  다시 녹음
                </button>
              </div>
            ) : null}

            {audioNotice ? <p className="mt-4 text-xs text-slate-500">{audioNotice}</p> : null}
          </div>
        </div>
      ) : null}

      {selectedMaterial ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 px-4">
          <div
            className="absolute inset-0"
            onClick={closeMaterialModal}
            aria-hidden="true"
          />
          <div className="relative z-10 w-full max-w-3xl rounded-3xl border border-slate-200 bg-white p-6 shadow-xl">
            <div className="flex items-start justify-between gap-3 border-b border-slate-200 pb-4 sm:items-center">
              <div className="min-w-0 flex-1">
                <p className="text-xs uppercase tracking-[0.3em] text-slate-400">자료</p>
                <h3 className="truncate text-base font-semibold text-slate-900 sm:text-lg">
                  {selectedMaterial.title || '자료'}
                </h3>
              </div>
              <button
                type="button"
                onClick={closeMaterialModal}
                className="shrink-0 whitespace-nowrap rounded-full border border-slate-300 px-3 py-2 text-xs text-slate-700 sm:px-4"
              >
                닫기
              </button>
            </div>

            {selectedMaterial.type === 'text' ? (
              <div className="mt-4 whitespace-pre-wrap text-sm text-slate-700">
                {selectedMaterial.body || '내용이 없습니다.'}
              </div>
            ) : (
              <div className="mt-4">
                {safeExternalUrl(selectedMaterial.url) ? (
                  <>
                    <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                      <div className="flex items-center gap-2">
                        <button
                          type="button"
                          onClick={goPrevPdfPage}
                          disabled={selectedPdfLoading || selectedPdfPage <= 1}
                          className="rounded-full border border-slate-300 px-3 py-1 text-xs text-slate-700 disabled:opacity-50"
                        >
                          이전 페이지
                        </button>
                        <span className="text-xs font-semibold text-slate-600">
                          {selectedPdfPage}
                          {selectedPdfPageCount ? ` / ${selectedPdfPageCount}` : ''}
                          {' '}페이지
                        </span>
                        <button
                          type="button"
                          onClick={goNextPdfPage}
                          disabled={
                            selectedPdfLoading ||
                            Boolean(selectedPdfPageCount && selectedPdfPage >= selectedPdfPageCount)
                          }
                          className="rounded-full border border-slate-300 px-3 py-1 text-xs text-slate-700 disabled:opacity-50"
                        >
                          다음 페이지
                        </button>
                      </div>
                      {selectedPdfLoading ? <span className="text-xs text-slate-500">PDF 로딩 중...</span> : null}
                    </div>
                    <div className="mx-auto w-full max-w-[920px]">
                      {selectedPdfMode === 'canvas' ? (
                        <>
                          <div className="flex justify-center rounded-2xl border border-slate-200 bg-white p-2">
                            <canvas
                              ref={pdfCanvasRef}
                              className="h-auto w-full max-w-full rounded-xl bg-white"
                            />
                          </div>
                          {selectedPdfRendering ? (
                            <p className="mt-2 text-center text-xs text-slate-500">페이지 렌더링 중...</p>
                          ) : null}
                          {selectedPdfError ? (
                            <p className="mt-2 text-center text-xs text-rose-600">{selectedPdfError}</p>
                          ) : null}
                        </>
                      ) : (
                        <iframe
                          key={`${selectedMaterial.id}-${selectedPdfPage}`}
                          title="meeting-material-fallback"
                          src={buildPdfIframeUrl(selectedMaterial.url, selectedPdfPage)}
                          referrerPolicy="no-referrer"
                          className="h-[78vh] min-h-[460px] w-full rounded-2xl border border-slate-200 bg-white"
                        />
                      )}
                    </div>
                  </>
                ) : (
                  <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-600">
                    PDF 파일을 불러올 수 없습니다.
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      ) : null}

    </div>
  );
}
