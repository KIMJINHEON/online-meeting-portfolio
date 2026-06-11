import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Hls from 'hls.js';
import * as pdfjsLibStatic from 'pdfjs-dist/legacy/build/pdf.mjs';
import AdminMeetingsView from './AdminMeetingsView.jsx';
import ExcludedPersonsModal from './ExcludedPersonsModal.jsx';
const ITEMS_PER_PAGE = 10;
const ADMIN_PDF_WORKER_SRC = '/pdf.worker.js';

const readUrlParams = () => {
  if (typeof window === 'undefined') {
    return { view: '', meetingId: null };
  }
  const params = new URLSearchParams(window.location.search);
  const rawMeetingId = params.get('meetingId');
  const parsedMeetingId = rawMeetingId === null ? null : Number.parseInt(rawMeetingId, 10);
  return {
    view: (params.get('view') || '').trim().toLowerCase(),
    meetingId: Number.isFinite(parsedMeetingId) && parsedMeetingId > 0 ? parsedMeetingId : null
  };
};

const emptyConfig = {
  baseUrl: '',
  defaultStreamKey: 'stream',
  pathTemplate: '/live/{streamKey}/playlist.m3u8',
  playbackQuery: '',
  defaultUrl: ''
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

const fetchJson = async (url, options = {}) => {
  const response = await fetch(url, { credentials: 'include', ...options });
  if (!response.ok) {
    const message = await response.text();
    const error = new Error(message || 'Request failed');
    error.status = response.status;
    throw error;
  }
  if (response.status === 204) {
    return null;
  }
  return response.json();
};

const getAudioDisplayName = (item) => {
  const uploader = trimUrl(item?.uploaderName);
  if (uploader) {
    return uploader;
  }
  const original = trimUrl(item?.originalName);
  if (original) {
    return original;
  }
  return `audio-${item?.id ?? 'unknown'}`;
};

export default function AdminApp() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [authChecked, setAuthChecked] = useState(false);
  const [streamKey, setStreamKey] = useState('stream');
  const [urlParams, setUrlParams] = useState(() => readUrlParams());
  const [currentMeeting, setCurrentMeeting] = useState(null);
  const [meetingResolveError, setMeetingResolveError] = useState('');
  const [accessToggleLoading, setAccessToggleLoading] = useState(false);
  const [isExcludedModalOpen, setIsExcludedModalOpen] = useState(false);

  // Gate for streamKey-dependent fetches. When a ?meetingId=N is in the URL,
  // we must NOT fire any data fetch with the still-default 'stream' streamKey
  // before the meeting metadata has been loaded. Otherwise legacy 'stream'
  // data flashes briefly before being replaced by the real meeting's data.
  const isMeetingContextReady = urlParams.meetingId === null || currentMeeting !== null;
  const [config, setConfig] = useState(emptyConfig);
  const [streamUrl, setStreamUrl] = useState('');
  const [pending, setPending] = useState([]);
  const [approved, setApproved] = useState([]);
  const [audioFiles, setAudioFiles] = useState([]);
  const [selectedAudioIds, setSelectedAudioIds] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [messageActionId, setMessageActionId] = useState(null);
  const [pendingLoaded, setPendingLoaded] = useState(false);
  const [approvedLoaded, setApprovedLoaded] = useState(false);
  const [audioLoading, setAudioLoading] = useState(false);
  const [audioError, setAudioError] = useState('');
  const [audioLoaded, setAudioLoaded] = useState(false);
  const [activeView, setActiveView] = useState('dashboard');
  const [rosterFile, setRosterFile] = useState(null);
  const [rosterUploading, setRosterUploading] = useState(false);
  const [rosterDownloading, setRosterDownloading] = useState(false);
  const [rosterUploadResult, setRosterUploadResult] = useState(null);
  const [rosterUploadError, setRosterUploadError] = useState('');
  const [rosterUploadNotice, setRosterUploadNotice] = useState(null);
  const [isAudioModalOpen, setIsAudioModalOpen] = useState(false);
  const [audioPreviewUrl, setAudioPreviewUrl] = useState('');
  const [audioPreviewName, setAudioPreviewName] = useState('');
  const [audioPreviewLoading, setAudioPreviewLoading] = useState(false);
  const [isStreamMetaModalOpen, setIsStreamMetaModalOpen] = useState(false);
  const [isDatePickerOpen, setIsDatePickerOpen] = useState(false);
  const [streamTitle, setStreamTitle] = useState('');
  const [scheduledStart, setScheduledStart] = useState('');
  const [startYear, setStartYear] = useState('');
  const [startMonth, setStartMonth] = useState('');
  const [startDay, setStartDay] = useState('');
  const [startHour, setStartHour] = useState('');
  const [startMinute, setStartMinute] = useState('');
  const [metaStatusLabel, setMetaStatusLabel] = useState('');
  const [streamLive, setStreamLive] = useState(false);
  const [streamLiveChecked, setStreamLiveChecked] = useState(false);
  const [metaSaving, setMetaSaving] = useState(false);
  const [metaError, setMetaError] = useState('');
  const [voteOpen, setVoteOpen] = useState(false);
  const [voteOpenLoading, setVoteOpenLoading] = useState(false);
  const [voteOpenError, setVoteOpenError] = useState('');
  const [attendanceVisible, setAttendanceVisible] = useState(false);
  const [attendanceVisibilityLoading, setAttendanceVisibilityLoading] = useState(false);
  const [attendanceVisibilityError, setAttendanceVisibilityError] = useState('');
  const [meetingElapsed, setMeetingElapsed] = useState(0);
  const [obsUptimeSeconds, setObsUptimeSeconds] = useState(null);
  const [attendanceSummary, setAttendanceSummary] = useState({
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
  });
  const [manualOnsiteInput, setManualOnsiteInput] = useState('');
  const [manualOnsiteError, setManualOnsiteError] = useState('');
  const [manualOnsiteSaving, setManualOnsiteSaving] = useState(false);
  const [manualFieldOnsiteInput, setManualFieldOnsiteInput] = useState('');
  const [manualFieldOnsiteError, setManualFieldOnsiteError] = useState('');
  const [manualFieldOnsiteSaving, setManualFieldOnsiteSaving] = useState(false);
  const [materials, setMaterials] = useState(() => {
    return [];
  });
  const [materialsPage, setMaterialsPage] = useState(1);
  const [showTextForm, setShowTextForm] = useState(false);
  const [textTitle, setTextTitle] = useState('');
  const [textBody, setTextBody] = useState('');
  const [materialsLoading, setMaterialsLoading] = useState(false);
  const [materialsError, setMaterialsError] = useState('');
  const [materialsLoaded, setMaterialsLoaded] = useState(false);
  const [pdfUploading, setPdfUploading] = useState(false);
  const [pdfUploadingName, setPdfUploadingName] = useState('');
  const [pendingPdfFile, setPendingPdfFile] = useState(null);
  const [isPendingPdfPreviewOpen, setIsPendingPdfPreviewOpen] = useState(false);
  const [pendingPdfPage, setPendingPdfPage] = useState(1);
  const [pendingPdfPageCount, setPendingPdfPageCount] = useState(null);
  const [pendingPdfLoading, setPendingPdfLoading] = useState(false);
  const [pendingPdfRendering, setPendingPdfRendering] = useState(false);
  const [pendingPdfError, setPendingPdfError] = useState('');
  const [editingMaterial, setEditingMaterial] = useState(null);
  const [editTitle, setEditTitle] = useState('');
  const [editBody, setEditBody] = useState('');
  const [editSaving, setEditSaving] = useState(false);
  const [editError, setEditError] = useState('');
  const [previewMaterial, setPreviewMaterial] = useState(null);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const videoRef = useRef(null);
  const hlsRef = useRef(null);
  const prevStreamLiveRef = useRef(false);
  const meetingStartRef = useRef(null);
  const pendingSectionRef = useRef(null);
  const pdfInputRef = useRef(null);
  const pendingPdfDocumentRef = useRef(null);
  const pendingPdfRenderTaskRef = useRef(null);
  const pendingPdfCanvasRef = useRef(null);

  useEffect(() => {
    if (pdfjsLibStatic?.GlobalWorkerOptions) {
      pdfjsLibStatic.GlobalWorkerOptions.workerSrc = ADMIN_PDF_WORKER_SRC;
    }
  }, []);


  useEffect(() => {
    let active = true;
    fetchJson('/api/admin/me')
      .then(() => {
        if (active) {
          setIsLoggedIn(true);
        }
      })
      .catch(() => {
        if (active) {
          setIsLoggedIn(false);
        }
      })
      .finally(() => {
        if (active) {
          setAuthChecked(true);
        }
      });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    const onPopState = () => setUrlParams(readUrlParams());
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);

  useEffect(() => {
    if (!isLoggedIn) {
      return;
    }
    if (urlParams.meetingId === null) {
      setCurrentMeeting(null);
      setMeetingResolveError('');
      return;
    }
    let active = true;
    setMeetingResolveError('');
    fetchJson(`/api/admin/meetings/${urlParams.meetingId}`)
      .then((meeting) => {
        if (!active || !meeting) {
          return;
        }
        setCurrentMeeting(meeting);
        const nextKey = (meeting.streamKey || '').trim();
        if (nextKey) {
          setStreamKey(nextKey);
        }
      })
      .catch((err) => {
        if (!active) {
          return;
        }
        setCurrentMeeting(null);
        if (err?.status === 404) {
          setMeetingResolveError(`회의 #${urlParams.meetingId} 를 찾을 수 없습니다.`);
        } else if (err?.status === 401) {
          setMeetingResolveError('로그인이 필요합니다.');
        } else {
          setMeetingResolveError('회의 정보를 불러오지 못했습니다.');
        }
      });
    return () => {
      active = false;
    };
  }, [isLoggedIn, urlParams.meetingId]);

  const loadPending = useCallback(async () => {
    if (!isLoggedIn || !isMeetingContextReady) {
      return;
    }
    if (!pendingLoaded) {
      setLoading(true);
    }
    try {
      const data = await fetchJson(
        `/api/admin/messages?streamKey=${encodeURIComponent(streamKey)}&status=pending&limit=100`
      );
      setPending(Array.isArray(data) ? data : []);
      setError('');
      setPendingLoaded(true);
    } catch (err) {
      setError('Failed to load pending messages.');
      setPendingLoaded(true);
    } finally {
      if (!pendingLoaded) {
        setLoading(false);
      }
    }
  }, [isLoggedIn, isMeetingContextReady, streamKey, pendingLoaded]);

  const loadApproved = useCallback(async () => {
    if (!isLoggedIn || !isMeetingContextReady) {
      return;
    }
    if (!approvedLoaded) {
      setLoading(true);
    }
    try {
      const data = await fetchJson(
        `/api/admin/messages?streamKey=${encodeURIComponent(streamKey)}&status=approved&limit=200`
      );
      setApproved(Array.isArray(data) ? data : []);
      setError('');
      setApprovedLoaded(true);
    } catch (err) {
      setError('Failed to load approved messages.');
      setApprovedLoaded(true);
    } finally {
      if (!approvedLoaded) {
        setLoading(false);
      }
    }
  }, [isLoggedIn, isMeetingContextReady, streamKey, approvedLoaded]);

  const loadAudio = useCallback(async () => {
    if (!isLoggedIn || !isMeetingContextReady) {
      return;
    }
    if (!audioLoaded) {
      setAudioLoading(true);
    }
    try {
      const data = await fetchJson(
        `/api/admin/audio?streamKey=${encodeURIComponent(streamKey)}&limit=100`
      );
      setAudioFiles(Array.isArray(data) ? data : []);
      setAudioError('');
      setAudioLoaded(true);
    } catch (err) {
      setAudioError('Failed to load audio uploads.');
      setAudioLoaded(true);
    } finally {
      if (!audioLoaded) {
        setAudioLoading(false);
      }
    }
  }, [isLoggedIn, isMeetingContextReady, streamKey, audioLoaded]);

  const loadStreamMeta = useCallback(async () => {
    if (!isLoggedIn || !isMeetingContextReady) {
      return;
    }
    try {
      const data = await fetchJson(
        `/api/admin/stream-meta?streamKey=${encodeURIComponent(streamKey || 'stream')}`
      );
      // Do not overwrite the in-progress form while the modal is open.
      // Stream meta polling is useful for status/live flag, but it should not reset inputs mid-edit.
      if (data?.scheduledStart) {
        const nextStart = data.scheduledStart.slice(0, 16);
      }
      setMetaStatusLabel(data?.statusLabel || '');
      if (typeof data?.live === 'boolean') {
        setStreamLive(data.live);
        setStreamLiveChecked(true);
      }

      const nextObsUptimeSeconds = Number(data?.obsUptimeSeconds);
      if (Number.isFinite(nextObsUptimeSeconds) && nextObsUptimeSeconds >= 0) {
        setObsUptimeSeconds(Math.floor(nextObsUptimeSeconds));
      } else if (data?.live === false) {
        setObsUptimeSeconds(null);
      }

      if (isStreamMetaModalOpen || metaSaving) {
        return;
      }

      setStreamTitle(data?.title || '');
      if (data?.scheduledStart) {
        const nextStart = data.scheduledStart.slice(0, 16);
        setScheduledStart(nextStart);
        const [datePart, timePart] = nextStart.split('T');
        if (datePart) {
          const [year, month, day] = datePart.split('-');
          setStartYear(year || '');
          setStartMonth(month || '');
          setStartDay(day || '');
        } else {
          setStartYear('');
          setStartMonth('');
          setStartDay('');
        }
        if (timePart) {
          const [hour, minute] = timePart.split(':');
          setStartHour(hour || '');
          setStartMinute(minute || '');
        } else {
          setStartHour('');
          setStartMinute('');
        }
      } else {
        setScheduledStart('');
        setStartYear('');
        setStartMonth('');
        setStartDay('');
        setStartHour('');
        setStartMinute('');
      }
    } catch (err) {
      // ignore load errors for now
    }
  }, [isLoggedIn, isMeetingContextReady, streamKey, isStreamMetaModalOpen, metaSaving]);

  const loadVoteStatus = useCallback(async () => {
    if (!isLoggedIn || !isMeetingContextReady) {
      return;
    }
    try {
      const data = await fetchJson(
        `/api/admin/vote/status?streamKey=${encodeURIComponent(streamKey || 'stream')}`
      );
      setVoteOpen(Boolean(data?.open));
      setVoteOpenError('');
    } catch (err) {
      setVoteOpen(false);
      setVoteOpenError('투표 상태를 불러오지 못했습니다.');
    }
  }, [isLoggedIn, isMeetingContextReady, streamKey]);

  const handleVoteToggle = async () => {
    if (voteOpenLoading) {
      return;
    }
    setVoteOpenLoading(true);
    try {
      const endpoint = voteOpen ? 'close' : 'open';
      const data = await fetchJson(
        `/api/admin/vote/${endpoint}?streamKey=${encodeURIComponent(streamKey || 'stream')}`,
        { method: 'POST' }
      );
      setVoteOpen(Boolean(data?.open));
      setVoteOpenError('');
    } catch (err) {
      setVoteOpenError(voteOpen ? '투표 마감에 실패했습니다.' : '투표 시작에 실패했습니다.');
    } finally {
      setVoteOpenLoading(false);
    }
  };

  const loadAttendanceVisibility = useCallback(async () => {
    if (!isLoggedIn || !isMeetingContextReady) {
      return;
    }
    try {
      const data = await fetchJson(
        `/api/admin/attendance-visibility/status?streamKey=${encodeURIComponent(streamKey || 'stream')}`
      );
      setAttendanceVisible(Boolean(data?.attendanceVisible));
      setAttendanceVisibilityError('');
    } catch (err) {
      setAttendanceVisible(false);
      setAttendanceVisibilityError('참석자 현황 공개 상태를 불러오지 못했습니다.');
    }
  }, [isLoggedIn, isMeetingContextReady, streamKey]);

  const handleAttendanceVisibilityToggle = async () => {
    if (attendanceVisibilityLoading) {
      return;
    }
    setAttendanceVisibilityLoading(true);
    try {
      const endpoint = attendanceVisible ? 'close' : 'open';
      const data = await fetchJson(
        `/api/admin/attendance-visibility/${endpoint}?streamKey=${encodeURIComponent(streamKey || 'stream')}`,
        { method: 'POST' }
      );
      setAttendanceVisible(Boolean(data?.attendanceVisible));
      setAttendanceVisibilityError('');
    } catch (err) {
      setAttendanceVisibilityError(
        attendanceVisible ? '회의자료 공개 전환에 실패했습니다.' : '참석자 현황 공개에 실패했습니다.'
      );
    } finally {
      setAttendanceVisibilityLoading(false);
    }
  };

  const applyAttendancePayload = useCallback((data, { syncInput = false } = {}) => {
    const online = Number.isFinite(Number(data?.online)) ? Number(data.online) : 0;
    const paper = Number.isFinite(Number(data?.paper)) ? Number(data.paper) : 0;
    const mail = Number.isFinite(Number(data?.mail)) ? Number(data.mail) : 0;
    const onsite = Number.isFinite(Number(data?.onsite)) ? Number(data.onsite) : 0;
    const fieldOnsite =
      Number.isFinite(Number(data?.fieldOnsite)) ? Number(data.fieldOnsite) : 0;
    const electronic =
      Number.isFinite(Number(data?.electronic)) ? Number(data.electronic) : 0;
    const paperSubmitted =
      Number.isFinite(Number(data?.paperSubmitted)) ? Number(data.paperSubmitted) : paper;
    const mailSubmitted =
      Number.isFinite(Number(data?.mailSubmitted)) ? Number(data.mailSubmitted) : mail;
    const onsiteSubmitted =
      Number.isFinite(Number(data?.onsiteSubmitted)) ? Number(data.onsiteSubmitted) : onsite;
    const fieldOnsiteSubmitted =
      Number.isFinite(Number(data?.fieldOnsiteSubmitted)) ? Number(data.fieldOnsiteSubmitted) : fieldOnsite;
    const electronicSubmitted =
      Number.isFinite(Number(data?.electronicSubmitted)) ? Number(data.electronicSubmitted) : electronic;
    const attendingTotal =
      Number.isFinite(Number(data?.attendingTotal))
        ? Number(data.attendingTotal)
        : paper + mail + onsite + fieldOnsite + electronic;
    const total =
      Number.isFinite(Number(data?.total))
        ? Number(data.total)
        : paperSubmitted + mailSubmitted + onsiteSubmitted + electronicSubmitted;
    setAttendanceSummary({
      online: Math.max(0, Math.floor(online)),
      paper: Math.max(0, Math.floor(paper)),
      mail: Math.max(0, Math.floor(mail)),
      onsite: Math.max(0, Math.floor(onsite)),
      fieldOnsite: Math.max(0, Math.floor(fieldOnsite)),
      electronic: Math.max(0, Math.floor(electronic)),
      paperSubmitted: Math.max(0, Math.floor(paperSubmitted)),
      mailSubmitted: Math.max(0, Math.floor(mailSubmitted)),
      onsiteSubmitted: Math.max(0, Math.floor(onsiteSubmitted)),
      fieldOnsiteSubmitted: Math.max(0, Math.floor(fieldOnsiteSubmitted)),
      electronicSubmitted: Math.max(0, Math.floor(electronicSubmitted)),
      attendingTotal: Math.max(0, Math.floor(attendingTotal)),
      total: Math.max(0, Math.floor(total))
    });

    const hasManualOnsite = Object.prototype.hasOwnProperty.call(data || {}, 'manualOnsite');
    const hasManualFieldOnsite = Object.prototype.hasOwnProperty.call(data || {}, 'manualFieldOnsite');
    if (!hasManualOnsite && !hasManualFieldOnsite) {
      return;
    }
    const manualOnsiteParsed = Number(data?.manualOnsite);
    const manualOnsite =
      Number.isFinite(manualOnsiteParsed) && manualOnsiteParsed >= 0
        ? Math.floor(manualOnsiteParsed)
        : null;
    const manualFieldOnsiteParsed = Number(data?.manualFieldOnsite);
    const manualFieldOnsite =
      Number.isFinite(manualFieldOnsiteParsed) && manualFieldOnsiteParsed >= 0
        ? Math.floor(manualFieldOnsiteParsed)
        : null;

    if (syncInput) {
      if (hasManualOnsite) {
        setManualOnsiteInput(manualOnsite === null ? '' : String(manualOnsite));
      }
      if (hasManualFieldOnsite) {
        setManualFieldOnsiteInput(manualFieldOnsite === null ? '' : String(manualFieldOnsite));
      }
      return;
    }
    if (hasManualOnsite) {
      setManualOnsiteInput((prev) => {
        if (String(prev || '').trim() !== '') {
          return prev;
        }
        return manualOnsite === null ? '' : String(manualOnsite);
      });
    }
    if (hasManualFieldOnsite) {
      setManualFieldOnsiteInput((prev) => {
        if (String(prev || '').trim() !== '') {
          return prev;
        }
        return manualFieldOnsite === null ? '' : String(manualFieldOnsite);
      });
    }
  }, []);

  const loadAttendance = useCallback(async () => {
    if (!isLoggedIn || !isMeetingContextReady) {
      return;
    }
    try {
      const data = await fetchJson(
        `/api/admin/attendance?streamKey=${encodeURIComponent(streamKey || 'stream')}`
      );
      applyAttendancePayload(data);
    } catch (err) {
      // Keep last known counts.
    }
  }, [isLoggedIn, isMeetingContextReady, streamKey, applyAttendancePayload]);

  const handleApplyManualOnsite = async () => {
    if (manualOnsiteSaving || !isLoggedIn) {
      return;
    }
    const raw = manualOnsiteInput.trim();
    let nextValue = null;
    if (!raw) {
      nextValue = null;
    } else {
      const parsed = Number(raw);
      if (!Number.isFinite(parsed) || parsed < 0) {
        setManualOnsiteError('0 이상의 숫자만 입력할 수 있습니다.');
        return;
      }
      nextValue = Math.floor(parsed);
    }

    setManualOnsiteSaving(true);
    setManualOnsiteError('');
    try {
      const data = await fetchJson(
        `/api/admin/attendance/manual-onsite?streamKey=${encodeURIComponent(streamKey || 'stream')}`,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({ onsite: nextValue })
        }
      );
      applyAttendancePayload(data, { syncInput: true });
    } catch (err) {
      setManualOnsiteError('직접 참석 값 저장에 실패했습니다.');
    } finally {
      setManualOnsiteSaving(false);
    }
  };

  const handleApplyManualFieldOnsite = async () => {
    if (manualFieldOnsiteSaving || !isLoggedIn) {
      return;
    }
    const raw = manualFieldOnsiteInput.trim();
    let nextValue = null;
    if (!raw) {
      nextValue = null;
    } else {
      const parsed = Number(raw);
      if (!Number.isFinite(parsed) || parsed < 0) {
        setManualFieldOnsiteError('0 이상의 숫자만 입력할 수 있습니다.');
        return;
      }
      nextValue = Math.floor(parsed);
    }

    setManualFieldOnsiteSaving(true);
    setManualFieldOnsiteError('');
    try {
      const data = await fetchJson(
        `/api/admin/attendance/manual-field-onsite?streamKey=${encodeURIComponent(streamKey || 'stream')}`,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({ onsite: nextValue })
        }
      );
      applyAttendancePayload(data, { syncInput: true });
    } catch (err) {
      setManualFieldOnsiteError('현장 참석 값 저장에 실패했습니다.');
    } finally {
      setManualFieldOnsiteSaving(false);
    }
  };

  const loadMaterials = useCallback(async () => {
    if (!isLoggedIn || !isMeetingContextReady) {
      return;
    }
    if (!materialsLoaded) {
      setMaterialsLoading(true);
    }
    try {
      const data = await fetchJson(
        `/api/admin/materials?streamKey=${encodeURIComponent(streamKey || 'stream')}&limit=200`
      );
      setMaterials(Array.isArray(data) ? data : []);
      setMaterialsError('');
      setMaterialsLoaded(true);
    } catch (err) {
      setMaterialsError('회의 자료를 불러오지 못했습니다.');
      setMaterialsLoaded(true);
    } finally {
      if (!materialsLoaded) {
        setMaterialsLoading(false);
      }
    }
  }, [isLoggedIn, isMeetingContextReady, streamKey, materialsLoaded]);

  useEffect(() => {
    fetch('/api/config')
      .then((res) => (res.ok ? res.json() : Promise.reject(res)))
      .then((data) => {
        const next = { ...emptyConfig, ...data };
        setConfig(next);
        const nextKey = (next.defaultStreamKey || 'stream').trim();
        setStreamKey(nextKey || 'stream');
      })
      .catch(() => {
        setStreamKey('stream');
      });
  }, []);

  useEffect(() => {
    setStreamUrl(resolveStreamUrl(config, streamKey));
  }, [config, streamKey]);

  useEffect(() => {
    const resolveView = () => {
      if (window.location.hash === '#materials') {
        return 'materials';
      }
      if (window.location.hash === '#roster') {
        return 'roster';
      }
      return 'dashboard';
    };
    setActiveView(resolveView());
    const handlePop = () => {
      setActiveView(resolveView());
    };
    window.addEventListener('popstate', handlePop);
    return () => window.removeEventListener('popstate', handlePop);
  }, []);

  useEffect(() => {
    if (!isLoggedIn) {
      return undefined;
    }
    loadPending();
    loadApproved();
    const id = window.setInterval(() => {
      loadPending();
      loadApproved();
    }, 5000);
    return () => window.clearInterval(id);
  }, [isLoggedIn, loadPending, loadApproved]);

  useEffect(() => {
    if (!isLoggedIn) {
      return undefined;
    }
    loadAudio();
    const id = window.setInterval(loadAudio, 8000);
    return () => window.clearInterval(id);
  }, [isLoggedIn, loadAudio]);

  useEffect(() => {
    if (!isLoggedIn) {
      return undefined;
    }
    if (isStreamMetaModalOpen) {
      return undefined;
    }
    loadStreamMeta();
    const id = window.setInterval(loadStreamMeta, 10000);
    return () => window.clearInterval(id);
  }, [isLoggedIn, isStreamMetaModalOpen, loadStreamMeta]);

  useEffect(() => {
    if (!isLoggedIn) {
      return undefined;
    }
    loadVoteStatus();
    const id = window.setInterval(loadVoteStatus, 10000);
    return () => window.clearInterval(id);
  }, [isLoggedIn, loadVoteStatus]);

  useEffect(() => {
    if (!isLoggedIn) {
      return undefined;
    }
    loadAttendanceVisibility();
    const id = window.setInterval(loadAttendanceVisibility, 10000);
    return () => window.clearInterval(id);
  }, [isLoggedIn, loadAttendanceVisibility]);

  useEffect(() => {
    if (!isLoggedIn) {
      return undefined;
    }
    loadAttendance();
    const id = window.setInterval(loadAttendance, 30000);
    return () => window.clearInterval(id);
  }, [isLoggedIn, loadAttendance]);

  useEffect(() => {
    if (!isLoggedIn) {
      return undefined;
    }
    loadMaterials();
    const id = window.setInterval(loadMaterials, 15000);
    return () => window.clearInterval(id);
  }, [isLoggedIn, loadMaterials]);

  useEffect(() => {
    if (!isLoggedIn || !streamLive) {
      return;
    }
    if (!Number.isFinite(obsUptimeSeconds) || obsUptimeSeconds < 0) {
      return;
    }

    const now = Date.now();
    const startMs = now - Math.floor(obsUptimeSeconds * 1000);
    meetingStartRef.current = startMs;
    setMeetingElapsed(Math.max(0, now - startMs));
  }, [isLoggedIn, streamLive, obsUptimeSeconds]);

  useEffect(() => {
    if (!isLoggedIn || !streamLive || !meetingStartRef.current) {
      return undefined;
    }
    const id = window.setInterval(() => {
      if (meetingStartRef.current) {
        setMeetingElapsed(Date.now() - meetingStartRef.current);
      }
    }, 1000);
    return () => window.clearInterval(id);
  }, [isLoggedIn, streamLive, obsUptimeSeconds]);

  const handleLogin = async (event) => {
    event.preventDefault();
    setError('');
    try {
      await fetchJson('/api/admin/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password })
      });
      setIsLoggedIn(true);
      setAuthChecked(true);
      setUsername('');
      setPassword('');
    } catch (err) {
      setError('Login failed.');
    }
  };

  const handleToggleCurrentMeetingAccess = async () => {
    if (!currentMeeting || accessToggleLoading) {
      return;
    }
    const nextOpen = !currentMeeting.accessOpen;
    setAccessToggleLoading(true);
    try {
      const updated = await fetchJson(`/api/admin/meetings/${currentMeeting.id}/access`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ open: nextOpen })
      });
      if (updated) {
        setCurrentMeeting(updated);
      }
    } catch (err) {
      window.alert('접속 상태 변경에 실패했습니다.');
    } finally {
      setAccessToggleLoading(false);
    }
  };

  const handleLogout = async () => {
    try {
      await fetchJson('/api/admin/logout', {
        method: 'POST'
      });
    } catch (err) {
      // ignore
    }
    setIsLoggedIn(false);
    setPending([]);
    setApproved([]);
    setAudioFiles([]);
    setSelectedAudioIds([]);
    setPendingLoaded(false);
    setApprovedLoaded(false);
    setAudioLoaded(false);
    setMaterialsLoaded(false);
    setMaterials([]);
    setActiveView('dashboard');
    setIsAudioModalOpen(false);
    setMeetingElapsed(0);
    setObsUptimeSeconds(null);
    meetingStartRef.current = null;
    setManualOnsiteInput('');
    setManualOnsiteValue(null);
    setManualOnsiteError('');
  };

  const updateStatus = async (item, action) => {
    if (!isLoggedIn || messageActionId !== null) {
      return;
    }
    setMessageActionId(item.id);
    try {
      await fetchJson(
        `/api/admin/messages/${item.id}/${action}?streamKey=${encodeURIComponent(streamKey || 'stream')}`,
        { method: 'POST' }
      );
      setPending((prev) => prev.filter((entry) => entry.id !== item.id));
      if (action === 'approve') {
        setApproved((prev) => [
          {
            ...item,
            status: 'approved'
          },
          ...prev
        ]);
      }
      setError('');
    } catch (err) {
      setError('Update failed.');
    } finally {
      setMessageActionId(null);
    }
  };

  const deleteMessage = async (item) => {
    if (!isLoggedIn || messageActionId !== null) {
      return;
    }
    if (!window.confirm('이 질의를 삭제할까요?')) {
      return;
    }
    setMessageActionId(item.id);
    try {
      await fetchJson(
        `/api/admin/messages/${item.id}?streamKey=${encodeURIComponent(streamKey || 'stream')}`,
        { method: 'DELETE' }
      );
      setPending((prev) => prev.filter((entry) => entry.id !== item.id));
      setApproved((prev) => prev.filter((entry) => entry.id !== item.id));
      setError('');
    } catch (err) {
      setError('질의 삭제에 실패했습니다.');
    } finally {
      setMessageActionId(null);
    }
  };

  const downloadAudio = async (item) => {
    if (!isLoggedIn) {
      return;
    }
    try {
      const response = await fetch(
        `/api/admin/audio/${item.id}/download?streamKey=${encodeURIComponent(streamKey || 'stream')}`,
        { credentials: 'include' }
      );
      if (!response.ok) {
        throw new Error('download failed');
      }
      const blob = await response.blob();
      const url = window.URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = item.originalName || `audio-${item.id}`;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      window.URL.revokeObjectURL(url);
      setAudioError('');
    } catch (err) {
      setAudioError('Download failed.');
    }
  };

  const previewAudio = async (item) => {
    if (!isLoggedIn) {
      return;
    }
    setAudioPreviewLoading(true);
    try {
      const response = await fetch(
        `/api/admin/audio/${item.id}/download?streamKey=${encodeURIComponent(streamKey || 'stream')}`,
        { credentials: 'include' }
      );
      if (!response.ok) {
        throw new Error('preview failed');
      }
      const blob = await response.blob();
      if (audioPreviewUrl) {
        URL.revokeObjectURL(audioPreviewUrl);
      }
      const url = window.URL.createObjectURL(blob);
      setAudioPreviewUrl(url);
      setAudioPreviewName(getAudioDisplayName(item));
      setAudioError('');
    } catch (err) {
      setAudioError('Preview failed.');
    } finally {
      setAudioPreviewLoading(false);
    }
  };

  const deleteAudio = async (id) => {
    if (!isLoggedIn) {
      return;
    }
    try {
      await fetchJson(
        `/api/admin/audio/${id}?streamKey=${encodeURIComponent(streamKey || 'stream')}`,
        { method: 'DELETE' }
      );
      setAudioFiles((prev) => prev.filter((item) => item.id !== id));
      setSelectedAudioIds((prev) => prev.filter((value) => value !== id));
      setAudioError('');
    } catch (err) {
      setAudioError('Delete failed.');
    }
  };

  const deleteSelectedAudio = async () => {
    if (!isLoggedIn || selectedAudioIds.length === 0) {
      return;
    }
    try {
      await fetchJson('/api/admin/audio/delete', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ streamKey: streamKey || 'stream', ids: selectedAudioIds })
      });
      setAudioFiles((prev) => prev.filter((item) => !selectedAudioIds.includes(item.id)));
      setSelectedAudioIds([]);
      setAudioError('');
    } catch (err) {
      setAudioError('Bulk delete failed.');
    }
  };

  const toggleAudioSelection = (id) => {
    setSelectedAudioIds((prev) =>
      prev.includes(id) ? prev.filter((value) => value !== id) : [...prev, id]
    );
  };

  const toggleAllAudio = () => {
    if (selectedAudioIds.length === audioFiles.length) {
      setSelectedAudioIds([]);
      return;
    }
    setSelectedAudioIds(audioFiles.map((item) => item.id));
  };

  const formatBytes = (value) => {
    if (!value) {
      return '0 MB';
    }
    const mb = value / (1024 * 1024);
    return `${mb.toFixed(1)} MB`;
  };

  const formatElapsed = (ms) => {
    const totalSeconds = Math.max(0, Math.floor(ms / 1000));
    const hours = String(Math.floor(totalSeconds / 3600)).padStart(2, '0');
    const minutes = String(Math.floor((totalSeconds % 3600) / 60)).padStart(2, '0');
    const seconds = String(totalSeconds % 60).padStart(2, '0');
    return `${hours}:${minutes}:${seconds}`;
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

  useEffect(() => {
    if (!streamLiveChecked || streamLive) {
      return;
    }
    meetingStartRef.current = null;
    setObsUptimeSeconds(null);
    setMeetingElapsed(0);
  }, [streamLiveChecked, streamLive]);

  const attemptAutoplay = (video) => {
    if (!video) {
      return;
    }
    video.muted = true;
    const promise = video.play();
    if (promise && typeof promise.catch === 'function') {
      promise.catch(() => { });
    }
  };

  const loadStream = (url) => {
    const video = videoRef.current;
    if (!video || !url) {
      return;
    }
    if (hlsRef.current) {
      hlsRef.current.destroy();
      hlsRef.current = null;
    }
    if (Hls.isSupported()) {
      const searchParams = new URLSearchParams(window.location.search);
      const debugHls =
        searchParams.get('hlsDebug') === '1' ||
        searchParams.get('hlsDebug') === 'true' ||
        searchParams.get('hlsDebug') === 'yes';
      const hls = new Hls({
        debug: debugHls,
        lowLatencyMode: false,
        liveSyncDuration: 45,
        liveMaxLatencyDuration: 120,
        liveSyncOnStallIncrease: 2,
        maxLiveSyncPlaybackRate: 1,
        maxBufferLength: 120,
        maxBufferHole: 0.5,
        backBufferLength: 180,
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
      hls.on(Hls.Events.MEDIA_ATTACHED, () => {
        attemptAutoplay(video);
      });
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        attemptAutoplay(video);
      });
      hls.on(Hls.Events.ERROR, (_, data) => {
        const details = data?.details;
        const type = data?.type;
        if (debugHls) {
          const responseCode = data?.response?.code;
          const errUrl = data?.url || data?.context?.url;
          // eslint-disable-next-line no-console
          console.warn('[admin hls]', { type, details, fatal: Boolean(data?.fatal), responseCode, url: errUrl });
        }

        if (
          details === Hls.ErrorDetails.BUFFER_STALLED_ERROR ||
          details === Hls.ErrorDetails.BUFFER_NUDGE_ON_STALL
        ) {
          try {
            hls.startLoad();
          } catch (err) {
            // ignore
          }
          attemptAutoplay(video);
          return;
        }

        if (!data?.fatal) {
          return;
        }

        if (type === Hls.ErrorTypes.NETWORK_ERROR) {
          try {
            hls.startLoad();
            return;
          } catch (err) {
            // fall through
          }
        }
        if (type === Hls.ErrorTypes.MEDIA_ERROR) {
          try {
            hls.recoverMediaError();
            return;
          } catch (err) {
            // fall through
          }
        }

        hls.destroy();
        hlsRef.current = null;
      });
      hls.loadSource(url);
      hls.attachMedia(video);
      return;
    }
    if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = url;
      video.addEventListener(
        'loadedmetadata',
        () => {
          attemptAutoplay(video);
        },
        { once: true }
      );
    }
  };

  const handlePdfUploadClick = () => {
    if (pdfUploading) {
      return;
    }
    if (pdfInputRef.current) {
      pdfInputRef.current.click();
    }
  };

  const closePendingPdfPreview = () => {
    if (pdfUploading) {
      return;
    }
    setPendingPdfFile(null);
    setIsPendingPdfPreviewOpen(false);
    setPendingPdfPage(1);
    setPendingPdfPageCount(null);
    setPendingPdfLoading(false);
    setPendingPdfRendering(false);
    setPendingPdfError('');
  };

  const confirmPendingPdfUpload = async () => {
    if (!pendingPdfFile || !isLoggedIn || pdfUploading) {
      return;
    }
    setPdfUploading(true);
    setPdfUploadingName(pendingPdfFile.name || '');
    setMaterialsLoading(true);
    setMaterialsError('');
    try {
      const formData = new FormData();
      formData.append('streamKey', streamKey || 'stream');
      formData.append('file', pendingPdfFile);
      const data = await fetchJson('/api/admin/materials/pdf', {
        method: 'POST',
        body: formData
      });
      if (data) {
        setMaterials((prev) => [data, ...prev]);
        setMaterialsPage(1);
      } else {
        await loadMaterials();
      }
      setPendingPdfFile(null);
      setIsPendingPdfPreviewOpen(false);
      setPendingPdfPage(1);
      setPendingPdfPageCount(null);
      setPendingPdfLoading(false);
      setPendingPdfRendering(false);
      setPendingPdfError('');
    } catch (err) {
      setMaterialsError('PDF 업로드에 실패했습니다.');
    } finally {
      setPdfUploading(false);
      setPdfUploadingName('');
      setMaterialsLoading(false);
    }
  };

  const handlePdfSelected = async (event) => {
    const file = event.target.files?.[0];
    if (!file || !isLoggedIn) {
      event.target.value = '';
      return;
    }
    setMaterialsError('');
    setPendingPdfFile(file);
    setIsPendingPdfPreviewOpen(true);
    setPendingPdfPage(1);
    setPendingPdfPageCount(null);
    setPendingPdfLoading(true);
    setPendingPdfRendering(false);
    setPendingPdfError('');
    event.target.value = '';
  };

  const handleAddTextMaterial = async () => {
    if (!textTitle.trim() && !textBody.trim()) {
      return;
    }
    if (!isLoggedIn) {
      return;
    }
    setMaterialsLoading(true);
    setMaterialsError('');
    try {
      const data = await fetchJson('/api/admin/materials/text', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          streamKey: streamKey || 'stream',
          title: textTitle.trim(),
          body: textBody.trim()
        })
      });
      if (data) {
        setMaterials((prev) => [data, ...prev]);
        setMaterialsPage(1);
      } else {
        await loadMaterials();
      }
      setTextTitle('');
      setTextBody('');
      setShowTextForm(false);
    } catch (err) {
      setMaterialsError('텍스트 자료 저장에 실패했습니다.');
    } finally {
      setMaterialsLoading(false);
    }
  };

  const openEditMaterial = (item) => {
    setEditingMaterial(item);
    setEditTitle(item.title || '');
    setEditBody(item.type === 'text' ? item.body || '' : '');
    setEditError('');
  };

  const openPreviewMaterial = (item) => {
    if (!item || item.type !== 'pdf') {
      return;
    }
    setPreviewMaterial(item);
  };

  const closePreviewMaterial = () => {
    setPreviewMaterial(null);
  };

  useEffect(() => {
    if (!isPendingPdfPreviewOpen || !pendingPdfFile) {
      if (pendingPdfRenderTaskRef.current) {
        try {
          pendingPdfRenderTaskRef.current.cancel();
        } catch (err) {
          // ignore
        }
        pendingPdfRenderTaskRef.current = null;
      }
      if (pendingPdfDocumentRef.current) {
        pendingPdfDocumentRef.current.destroy().catch(() => { });
        pendingPdfDocumentRef.current = null;
      }
      return undefined;
    }

    let active = true;
    setPendingPdfLoading(true);
    setPendingPdfError('');
    setPendingPdfPage(1);
    setPendingPdfPageCount(null);

    if (pendingPdfRenderTaskRef.current) {
      try {
        pendingPdfRenderTaskRef.current.cancel();
      } catch (err) {
        // ignore
      }
      pendingPdfRenderTaskRef.current = null;
    }
    if (pendingPdfDocumentRef.current) {
      pendingPdfDocumentRef.current.destroy().catch(() => { });
      pendingPdfDocumentRef.current = null;
    }

    (async () => {
      try {
        const buffer = await pendingPdfFile.arrayBuffer();
        if (!active) {
          return;
        }
        const loadingTask = pdfjsLibStatic.getDocument({
          data: new Uint8Array(buffer),
          disableWorker: true
        });
        const doc = await loadingTask.promise;
        if (!active) {
          doc.destroy().catch(() => { });
          return;
        }
        pendingPdfDocumentRef.current = doc;
        setPendingPdfPageCount(Math.max(1, Number(doc.numPages) || 1));
      } catch (err) {
        if (!active) {
          return;
        }
        const detail = `${err?.name || 'Error'}${err?.message ? `: ${err.message}` : ''}`;
        setPendingPdfError(`미리보기를 불러오지 못했습니다. (${detail})`);
      } finally {
        if (!active) {
          return;
        }
        setPendingPdfLoading(false);
      }
    })();

    return () => {
      active = false;
      if (pendingPdfRenderTaskRef.current) {
        try {
          pendingPdfRenderTaskRef.current.cancel();
        } catch (err) {
          // ignore
        }
        pendingPdfRenderTaskRef.current = null;
      }
      if (pendingPdfDocumentRef.current) {
        pendingPdfDocumentRef.current.destroy().catch(() => { });
        pendingPdfDocumentRef.current = null;
      }
    };
  }, [isPendingPdfPreviewOpen, pendingPdfFile]);

  useEffect(() => {
    if (!isPendingPdfPreviewOpen || pendingPdfLoading) {
      setPendingPdfRendering(false);
      return undefined;
    }
    const doc = pendingPdfDocumentRef.current;
    const canvas = pendingPdfCanvasRef.current;
    if (!doc || !canvas) {
      return undefined;
    }

    const docPageCount = Math.max(1, Number(doc.numPages) || 1);
    if (!pendingPdfPageCount || pendingPdfPageCount !== docPageCount) {
      setPendingPdfPageCount(docPageCount);
    }
    const pageNumber = Math.max(1, Math.min(docPageCount, pendingPdfPage));

    let active = true;
    setPendingPdfRendering(true);

    if (pendingPdfRenderTaskRef.current) {
      try {
        pendingPdfRenderTaskRef.current.cancel();
      } catch (err) {
        // ignore
      }
      pendingPdfRenderTaskRef.current = null;
    }

    (async () => {
      try {
        const page = await doc.getPage(pageNumber);
        if (!active) {
          return;
        }
        const baseViewport = page.getViewport({ scale: 1 });
        const containerWidth = Math.max(360, Math.floor(canvas.parentElement?.clientWidth || baseViewport.width));
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

        const renderTask = page.render({ canvasContext: context, viewport });
        pendingPdfRenderTaskRef.current = renderTask;
        await renderTask.promise;
      } catch (err) {
        if (!active) {
          return;
        }
        if (err?.name === 'RenderingCancelledException') {
          return;
        }
        const detail = `${err?.name || 'Error'}${err?.message ? `: ${err.message}` : ''}`;
        setPendingPdfError(`미리보기를 표시하지 못했습니다. (${detail})`);
      } finally {
        if (!active) {
          return;
        }
        setPendingPdfRendering(false);
      }
    })();

    return () => {
      active = false;
      if (pendingPdfRenderTaskRef.current) {
        try {
          pendingPdfRenderTaskRef.current.cancel();
        } catch (err) {
          // ignore
        }
        pendingPdfRenderTaskRef.current = null;
      }
    };
  }, [isPendingPdfPreviewOpen, pendingPdfLoading, pendingPdfPage, pendingPdfPageCount]);

  const handleSaveEdit = async () => {
    if (!editingMaterial || !isLoggedIn) {
      return;
    }
    setEditSaving(true);
    setEditError('');
    try {
      const payload = { title: editTitle.trim() };
      if (editingMaterial.type === 'text') {
        payload.body = editBody;
      }
      const updated = await fetchJson(
        `/api/admin/materials/${editingMaterial.id}?streamKey=${encodeURIComponent(streamKey || 'stream')}`,
        {
          method: 'PATCH',
          headers: {
            'Content-Type': 'application/json'
          },
          body: JSON.stringify(payload)
        }
      );
      setMaterials((prev) => prev.map((item) => (item.id === updated.id ? updated : item)));
      setEditingMaterial(null);
    } catch (err) {
      setEditError('수정에 실패했습니다.');
    } finally {
      setEditSaving(false);
    }
  };

  const handleDeleteMaterial = async (item) => {
    if (!isLoggedIn) {
      return;
    }
    if (!window.confirm('이 회의자료를 삭제할까요?')) {
      return;
    }
    setMaterialsLoading(true);
    setMaterialsError('');
    try {
      await fetchJson(
        `/api/admin/materials/${item.id}?streamKey=${encodeURIComponent(streamKey || 'stream')}`,
        { method: 'DELETE' }
      );
      setMaterials((prev) => prev.filter((entry) => entry.id !== item.id));
    } catch (err) {
      setMaterialsError('삭제에 실패했습니다.');
    } finally {
      setMaterialsLoading(false);
    }
  };

  const totalPages = Math.max(1, Math.ceil(materials.length / ITEMS_PER_PAGE));
  const safePage = Math.min(materialsPage, totalPages);
  const pagedMaterials = materials.slice((safePage - 1) * ITEMS_PER_PAGE, safePage * ITEMS_PER_PAGE);

  const navigateTo = useCallback((view) => {
    const nextView = view === 'materials' ? 'materials' : view === 'roster' ? 'roster' : 'dashboard';
    setActiveView(nextView);
    const baseUrl = `${window.location.pathname}${window.location.search}`;
    const nextUrl =
      nextView === 'materials'
        ? `${baseUrl}#materials`
        : nextView === 'roster'
          ? `${baseUrl}#roster`
          : baseUrl;
    window.history.pushState({ view: nextView }, '', nextUrl);
  }, []);

  const handleRosterSelected = (event) => {
    const file = event?.target?.files?.[0] || null;
    setRosterUploadResult(null);
    setRosterUploadError('');
    setRosterUploadNotice(null);
    if (!file) {
      setRosterFile(null);
      return;
    }
    const name = String(file.name || '').toLowerCase();
    if (!name.endsWith('.xlsx')) {
      setRosterFile(null);
      setRosterUploadError('.xlsx 형식의 엑셀 파일만 업로드할 수 있습니다. (.xls는 다른 이름으로 저장 -> .xlsx로 변환 후 업로드)');
      return;
    }
    setRosterFile(file);
  };

  const handleRosterUpload = async () => {
    if (!isLoggedIn) {
      return;
    }
    if (!rosterFile) {
      setRosterUploadError('업로드할 파일을 선택해주세요.');
      return;
    }
    const name = String(rosterFile.name || '').toLowerCase();
    if (!name.endsWith('.xlsx')) {
      setRosterUploadError('.xlsx 형식의 엑셀 파일만 업로드할 수 있습니다.');
      return;
    }
    setRosterUploading(true);
    setRosterUploadError('');
    setRosterUploadResult(null);
    setRosterUploadNotice(null);
    try {
      const form = new FormData();
      form.append('file', rosterFile);
      const data = await fetchJson(
        `/api/admin/roster/upload?streamKey=${encodeURIComponent(streamKey || 'stream')}`,
        {
          method: 'POST',
          body: form
        }
      );
      setRosterUploadResult(data);
      const inserted = Math.max(0, Number(data?.inserted || 0));
      const skipped = Math.max(0, Number(data?.skipped || 0));
      setRosterUploadNotice({
        title: inserted > 0 ? '명부 업로드가 완료되었습니다.' : '명부 업로드 처리가 완료되었습니다.',
        summary: `${inserted}건 등록, ${skipped}건 스킵`,
        errors: Array.isArray(data?.errors) ? data.errors.slice(0, 8) : []
      });
    } catch (err) {
      setRosterUploadError(err instanceof Error ? err.message : '명부 업로드에 실패했습니다.');
    } finally {
      setRosterUploading(false);
    }
  };

  const handleRosterDownload = async () => {
    if (!isLoggedIn) {
      return;
    }
    setRosterUploadError('');
    try {
      setRosterDownloading(true);
      const response = await fetch(
        `/api/admin/roster/download?streamKey=${encodeURIComponent(streamKey || 'stream')}`,
        {
          credentials: 'include'
        }
      );
      if (!response.ok) {
        throw new Error('download_failed');
      }
      const blob = await response.blob();
      const url = window.URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = 'voter_roster_export.xlsx';
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      window.URL.revokeObjectURL(url);
    } catch (err) {
      setRosterUploadError('명부 다운로드에 실패했습니다.');
    } finally {
      setRosterDownloading(false);
    }
  };

  const buildScheduledStart = () => {
    if (!startYear || !startMonth || !startDay || !startHour || !startMinute) {
      return '';
    }
    const month = String(startMonth).padStart(2, '0');
    const day = String(startDay).padStart(2, '0');
    return `${startYear}-${month}-${day}T${startHour}:${startMinute}`;
  };

  const yearOptions = useMemo(() => {
    const currentYear = new Date().getFullYear();
    return Array.from({ length: 4 }, (_, index) => String(currentYear + index));
  }, []);

  const handleSaveStreamMeta = async () => {
    if (!isLoggedIn) {
      return;
    }
    setMetaSaving(true);
    setMetaError('');
    try {
      const nextScheduledStart = buildScheduledStart();
      if (!streamTitle.trim()) {
        setMetaError('방송 제목을 입력해주세요.');
        setMetaSaving(false);
        return;
      }
      if (!nextScheduledStart) {
        setMetaError('시작 시간을 모두 선택해주세요.');
        setMetaSaving(false);
        return;
      }
      const response = await fetchJson('/api/admin/stream-meta', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          streamKey: streamKey || 'stream',
          title: streamTitle.trim(),
          scheduledStart: nextScheduledStart
        })
      });
      setScheduledStart(nextScheduledStart);
      setMetaStatusLabel(response?.statusLabel || '');
      if (typeof response?.live === 'boolean') {
        setStreamLive(response.live);
        setStreamLiveChecked(true);
      }
      const nextObsUptimeSeconds = Number(response?.obsUptimeSeconds);
      if (Number.isFinite(nextObsUptimeSeconds) && nextObsUptimeSeconds >= 0) {
        setObsUptimeSeconds(Math.floor(nextObsUptimeSeconds));
      }
      setIsStreamMetaModalOpen(false);
    } catch (err) {
      setMetaError('방송 정보 저장에 실패했습니다.');
    } finally {
      setMetaSaving(false);
    }
  };

  const questionItems = useMemo(() => {
    const pendingItems = pending.map((item) => ({ ...item, status: 'pending' }));
    const approvedItems = approved.map((item) => ({ ...item, status: 'approved' }));
    return [...pendingItems, ...approvedItems].sort((a, b) => b.id - a.id);
  }, [pending, approved]);
  const displayedOnsite = attendanceSummary.online;
  const displayedFieldOnsite = attendanceSummary.fieldOnsite;
  const displayedAttendingTotal = attendanceSummary.attendingTotal;
  const displayedTotal = attendanceSummary.total;

  useEffect(() => {
    if (streamUrl) {
      loadStream(streamUrl);
    }
    return () => {
      if (hlsRef.current) {
        hlsRef.current.destroy();
        hlsRef.current = null;
      }
    };
  }, [streamUrl]);

  useEffect(() => {
    if (!isLoggedIn || !streamLive || !streamUrl) {
      return;
    }
    const video = videoRef.current;
    const hasSource = Boolean(hlsRef.current) || Boolean(video?.src);
    if (!hasSource) {
      loadStream(streamUrl);
    }
  }, [isLoggedIn, streamLive, streamUrl]);

  useEffect(() => {
    const wasLive = prevStreamLiveRef.current;
    prevStreamLiveRef.current = streamLive;
    if (!isLoggedIn || !streamUrl) {
      return;
    }
    if (streamLive && !wasLive) {
      loadStream(streamUrl);
    }
  }, [isLoggedIn, streamLive, streamUrl]);

  useEffect(() => {
    return () => {
      if (audioPreviewUrl) {
        URL.revokeObjectURL(audioPreviewUrl);
      }
    };
  }, [audioPreviewUrl]);

  if (!authChecked) {
    return (
      <div className="relative min-h-screen overflow-hidden bg-slate-50 text-slate-900">
        <div className="pointer-events-none absolute inset-0 bg-gradient-to-br from-white via-slate-50 to-slate-100" />
        <div className="relative z-10 mx-auto flex min-h-screen w-full max-w-md items-center justify-center px-6">
          <div className="rounded-3xl border border-slate-200 bg-white px-6 py-8 text-sm text-slate-500 shadow-xl">
            Checking session...
          </div>
        </div>
      </div>
    );
  }

  if (!isLoggedIn) {
    return (
      <div className="relative min-h-screen overflow-hidden bg-slate-50 text-slate-900">
        <div className="pointer-events-none absolute inset-0 bg-gradient-to-br from-white via-slate-50 to-slate-100" />
        <div className="relative z-10 mx-auto flex min-h-screen w-full max-w-md items-center px-6">
          <form
            onSubmit={handleLogin}
            className="w-full rounded-3xl border border-slate-200 bg-white p-8 shadow-xl"
          >
            <h1 className="text-2xl font-semibold text-slate-900">Admin Login</h1>
            <p className="mt-2 text-sm text-slate-500">Approve messages before they go live.</p>
            <div className="mt-6 space-y-4">
              <input
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                placeholder="Username"
                className="w-full rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-900 outline-none focus:border-amber-400/60"
              />
              <input
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                type="password"
                placeholder="Password"
                className="w-full rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-900 outline-none focus:border-amber-400/60"
              />
              {error ? <p className="text-sm text-rose-500">{error}</p> : null}
              <button
                type="submit"
                className="w-full rounded-full bg-amber-500 px-5 py-2 text-sm font-semibold text-white transition hover:bg-amber-400"
              >
                Sign in
              </button>
            </div>
          </form>
        </div>
      </div>
    );
  }

  // /admin.html (회의 미선택) 또는 ?view=meetings → 회의 관리 화면이 곧 메인 페이지.
  // ?meetingId=N 일 때만 그 회의의 어드민 화면으로 진입.
  if (urlParams.meetingId === null || urlParams.view === 'meetings') {
    return <AdminMeetingsView />;
  }

  return (
    <div className="relative min-h-screen bg-slate-50 text-slate-900">
      <div className="pointer-events-none absolute inset-0 bg-gradient-to-br from-white via-slate-50 to-slate-100" />

      <header className="sticky top-0 z-40 border-b border-slate-200 bg-white/95 text-slate-900 shadow-md backdrop-blur">
        <div className="mx-auto flex w-full max-w-[1760px] flex-wrap items-center justify-between gap-4 px-6 py-5 2xl:max-w-[1880px]">
          <div className="flex min-w-0 items-center gap-3">
            <span className="text-xl font-bold tracking-wide text-slate-900">관리자 페이지</span>
            <span className="text-slate-300">|</span>
            {currentMeeting ? (
              <>
                <span className="truncate text-base font-medium text-slate-900">
                  {currentMeeting.title}{' '}
                  <span className="text-slate-400">(#{currentMeeting.id})</span>
                </span>
                <span
                  className={
                    currentMeeting.accessOpen
                      ? 'rounded-full bg-emerald-100 px-2.5 py-1 text-xs font-semibold text-emerald-700'
                      : 'rounded-full bg-slate-200 px-2.5 py-1 text-xs font-semibold text-slate-600'
                  }
                >
                  {currentMeeting.accessOpen ? '접속 허용 중' : '접속 차단 중'}
                </span>
              </>
            ) : urlParams.meetingId !== null ? (
              <span className="truncate text-base text-slate-500">
                {meetingResolveError || '회의 정보 불러오는 중...'}
              </span>
            ) : (
              <span className="truncate text-base font-medium text-slate-500">
                회의 미선택
              </span>
            )}
          </div>
          <div className="flex flex-wrap items-center gap-2">
            {currentMeeting ? (
              <button
                type="button"
                onClick={handleToggleCurrentMeetingAccess}
                disabled={accessToggleLoading}
                className={
                  currentMeeting.accessOpen
                    ? 'rounded-full bg-rose-500 px-5 py-2 text-sm font-semibold text-white shadow-sm transition hover:bg-rose-400 disabled:opacity-50'
                    : 'rounded-full bg-emerald-500 px-5 py-2 text-sm font-semibold text-white shadow-sm transition hover:bg-emerald-400 disabled:opacity-50'
                }
              >
                {accessToggleLoading
                  ? '변경 중...'
                  : currentMeeting.accessOpen
                  ? '접속 차단'
                  : '접속 허용'}
              </button>
            ) : null}
            <a
              href="/admin.html?view=meetings"
              className="rounded-full bg-amber-500 px-5 py-2 text-sm font-semibold text-white shadow-sm transition hover:bg-amber-400"
            >
              회의 관리
            </a>
            {activeView !== 'dashboard' ? (
              <button
                onClick={() => navigateTo('dashboard')}
                className="rounded-full border border-slate-300 px-4 py-2 text-sm text-slate-700 transition hover:border-slate-400 hover:bg-slate-50"
              >
                대시보드
              </button>
            ) : null}
            <button
              onClick={handleLogout}
              className="rounded-full border border-slate-300 px-5 py-2 text-sm text-slate-700 transition hover:border-slate-400 hover:bg-slate-50"
            >
              Logout
            </button>
          </div>
        </div>
      </header>

      <div className="relative z-10 mx-auto flex w-full max-w-[1760px] flex-col gap-6 px-6 py-8 2xl:max-w-[1880px]">

        {activeView === 'materials' ? (
          <section className="grid gap-4">
            <div className="flex flex-wrap items-center justify-between gap-3 rounded-3xl border border-slate-200 bg-white p-6 shadow-sm">
              <div>
                <p className="text-xs uppercase tracking-[0.3em] text-slate-400">회의자료</p>
                <h2 className="text-2xl font-semibold text-slate-900">회의 자료 리스트</h2>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <button
                  onClick={handlePdfUploadClick}
                  disabled={pdfUploading}
                  className={`rounded-full px-4 py-2 text-xs font-semibold text-white ${pdfUploading ? 'cursor-not-allowed bg-amber-300' : 'bg-amber-500'}`}
                >
                  {pdfUploading ? 'PDF 업로드 중...' : 'PDF 업로드'}
                </button>
                <button
                  onClick={() => setShowTextForm((prev) => !prev)}
                  className="rounded-full border border-slate-300 px-4 py-2 text-xs text-slate-700"
                >
                  텍스트 작성
                </button>
              </div>
            </div>
            {pdfUploading ? (
              <div className="mt-4 flex items-center gap-2 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-800">
                <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-amber-500 border-t-transparent" />
                <span>
                  PDF 업로드 중입니다{pdfUploadingName ? `: ${pdfUploadingName}` : ''}. 완료될 때까지 잠시만 기다려 주세요.
                </span>
              </div>
            ) : null}

            {showTextForm ? (
              <div className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm">
                <div className="grid gap-3">
                  <input
                    value={textTitle}
                    onChange={(event) => setTextTitle(event.target.value)}
                    placeholder="자료 제목"
                    className="rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-900 outline-none focus:border-amber-400/60"
                  />
                  <textarea
                    value={textBody}
                    onChange={(event) => setTextBody(event.target.value)}
                    placeholder="회의 자료 내용을 입력하세요"
                    rows={6}
                    className="rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-900 outline-none focus:border-amber-400/60"
                  />
                  <div className="flex gap-2">
                    <button
                      onClick={handleAddTextMaterial}
                      className="rounded-2xl bg-emerald-500 px-4 py-2 text-xs font-semibold text-white"
                    >
                      등록
                    </button>
                    <button
                      onClick={() => setShowTextForm(false)}
                      className="rounded-2xl border border-slate-300 px-4 py-2 text-xs text-slate-700"
                    >
                      취소
                    </button>
                  </div>
                </div>
              </div>
            ) : null}

            {materialsError ? (
              <div className="rounded-3xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-600 shadow-sm">
                {materialsError}
              </div>
            ) : null}

            <div className="grid gap-3">
              {materialsLoading && pagedMaterials.length === 0 ? (
                <div className="rounded-3xl border border-slate-200 bg-white p-6 text-sm text-slate-500 shadow-sm">
                  회의 자료를 불러오는 중입니다...
                </div>
              ) : null}
              {!materialsLoading && pagedMaterials.length === 0 ? (
                <div className="rounded-3xl border border-slate-200 bg-white p-6 text-sm text-slate-500 shadow-sm">
                  등록된 회의 자료가 없습니다.
                </div>
              ) : null}

              {pagedMaterials.map((item) => {
                const safePdfUrl = item.type === 'pdf' ? safeExternalUrl(item.url) : '';
                return (
                  <div key={item.id} className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm">
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <div>
                        <p className="text-xs uppercase tracking-[0.3em] text-slate-400">
                          {item.type === 'pdf' ? 'PDF' : 'TEXT'}
                        </p>
                        <h3 className="text-lg font-semibold text-slate-900">{item.title}</h3>
                        <p className="text-xs text-slate-400">{new Date(item.createdAt).toLocaleString()}</p>
                      </div>
                      <div className="flex flex-wrap items-center gap-2">
                        {safePdfUrl ? (
                          <button
                            onClick={() => openPreviewMaterial(item)}
                            className="rounded-full border border-indigo-300 px-4 py-2 text-xs text-indigo-700"
                          >
                            미리보기
                          </button>
                        ) : null}
                        {safePdfUrl ? (
                          <a
                            href={safePdfUrl}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="rounded-full border border-slate-300 px-4 py-2 text-xs text-slate-700"
                          >
                            파일 보기
                          </a>
                        ) : null}
                        <button
                          onClick={() => openEditMaterial(item)}
                          className="rounded-full border border-slate-300 px-4 py-2 text-xs text-slate-700"
                        >
                          수정
                        </button>
                        <button
                          onClick={() => handleDeleteMaterial(item)}
                          className="rounded-full border border-rose-200 px-4 py-2 text-xs text-rose-600"
                        >
                          삭제
                        </button>
                      </div>
                    </div>
                    {item.type === 'text' && item.body ? (
                      <p className="mt-3 text-sm text-slate-700">{item.body}</p>
                    ) : null}
                  </div>
                );
              })}
            </div>

            <div className="flex flex-wrap items-center justify-center gap-2">
              <button
                onClick={() => setMaterialsPage((prev) => Math.max(1, prev - 1))}
                className="rounded-full border border-slate-300 px-3 py-1 text-xs text-slate-700"
              >
                이전
              </button>
              <span className="text-xs text-slate-400">
                {safePage} / {totalPages}
              </span>
              <button
                onClick={() => setMaterialsPage((prev) => Math.min(totalPages, prev + 1))}
                className="rounded-full border border-slate-300 px-3 py-1 text-xs text-slate-700"
              >
                다음
              </button>
            </div>
          </section>
        ) : activeView === 'roster' ? (
          <section className="grid gap-4">
            <div className="flex flex-wrap items-center justify-between gap-3 rounded-3xl border border-slate-200 bg-white p-6 shadow-sm">
              <div>
                <p className="text-xs uppercase tracking-[0.3em] text-slate-400">명부</p>
                <h2 className="text-2xl font-semibold text-slate-900">엑셀 명부 업로드</h2>
              </div>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={handleRosterDownload}
                  disabled={rosterDownloading}
                  className={`rounded-full px-4 py-2 text-xs font-semibold text-white ${rosterDownloading ? 'bg-slate-400' : 'bg-indigo-600 hover:bg-indigo-500'
                    }`}
                >
                  {rosterDownloading ? '다운로드 중...' : '명부 다운로드'}
                </button>
                <button
                  onClick={() => navigateTo('dashboard')}
                  className="rounded-full border border-slate-300 px-4 py-2 text-xs text-slate-700"
                >
                  대시보드
                </button>
              </div>
            </div>

            <div className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm">
              <p className="text-sm text-slate-700">
                파일 형식은 <span className="font-semibold">.xlsx</span> 엑셀만 지원합니다.
                (.xls는 지원하지 않으니 <span className="font-semibold">다른 이름으로 저장</span>으로 .xlsx로 변환 후 업로드)
                업로드하면 기존 명부는 삭제되고 새 명부로 교체됩니다.
              </p>
              <p className="mt-2 text-xs text-slate-500">
                필수 컬럼: 이름, 생년월일, 휴대폰번호 (그 외 컬럼은 있으면 저장됩니다.)
              </p>

              <div className="mt-5 grid gap-3">
                <input
                  type="file"
                  accept=".xlsx"
                  onChange={handleRosterSelected}
                  className="w-full rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-700"
                />
                <button
                  type="button"
                  onClick={handleRosterUpload}
                  disabled={rosterUploading}
                  className={`rounded-2xl px-4 py-2 text-sm font-semibold text-white ${rosterUploading ? 'bg-slate-400' : 'bg-indigo-600 hover:bg-indigo-500'
                    }`}
                >
                  {rosterUploading ? '업로드 중...' : '명부 업로드'}
                </button>
                {rosterUploading ? (
                  <p className="text-xs text-slate-500">
                    명부를 업로드 중입니다. 완료되면 안내 모달이 표시됩니다.
                  </p>
                ) : null}
              </div>

              {rosterUploadError ? (
                <div className="mt-4 rounded-2xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-600">
                  {rosterUploadError}
                </div>
              ) : null}

              {rosterUploadResult ? (
                <div
                  className={`mt-4 rounded-2xl border p-4 text-sm ${(rosterUploadResult.inserted ?? 0) > 0
                    ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
                    : 'border-rose-200 bg-rose-50 text-rose-700'
                    }`}
                >
                  {(rosterUploadResult.inserted ?? 0) > 0 ? '업로드 완료' : '업로드 실패'}: {rosterUploadResult.inserted ?? 0}건 등록,{' '}
                  {rosterUploadResult.skipped ?? 0}건 스킵
                  {Array.isArray(rosterUploadResult.errors) && rosterUploadResult.errors.length > 0 ? (
                    <div
                      className={`mt-2 text-xs ${(rosterUploadResult.inserted ?? 0) > 0 ? 'text-emerald-800' : 'text-rose-800'
                        }`}
                    >
                      {rosterUploadResult.errors.slice(0, 8).map((msg, idx) => (
                        <div key={`${idx}-${msg}`}>{msg}</div>
                      ))}
                    </div>
                  ) : null}
                </div>
              ) : null}
            </div>
          </section>
        ) : (
          <>
            <section className="grid gap-5 lg:grid-cols-[260px_minmax(0,1fr)_420px] xl:grid-cols-[280px_minmax(0,1fr)_460px]">
              <div className="min-w-0 grid gap-4 pr-[10px]">
                <div className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm">
                  <p className="text-xs uppercase tracking-[0.3em] text-slate-400">회의시간</p>
                  <p className="mt-3 text-3xl font-semibold text-slate-900">{formatElapsed(meetingElapsed)}</p>
                  <p className="mt-2 text-[12px] leading-5 text-slate-600">
                    <span className="font-semibold text-slate-700">참석 기준:</span> 서면결의, 우편 제출, 전자투표 항목에 값이 있는 대상 중
                    본인인증 후 접속 인원
                  </p>
                  <p className="mt-1 text-[12px] leading-5 text-slate-600">
                    <span className="font-semibold text-slate-700">제출 기준:</span> 서면결의, 우편 제출, 전자투표 항목에 값이 있는 명부 건수
                  </p>

                  <div className="mt-5 border-t border-slate-100 pt-5">
                    <p className="text-xs uppercase tracking-[0.3em] text-slate-400">출석 현황</p>
                    <div className="mt-3 grid grid-cols-[1fr_52px_52px] items-center gap-2 border-b border-slate-100 pb-2 text-sm font-normal">
                      <span className="text-slate-400">구분</span>
                      <span className="text-center text-slate-600">참석</span>
                      <span className="text-center text-slate-600">제출</span>
                    </div>
                    <div className="mt-2 grid gap-2 text-sm text-slate-700">
                      <div className="grid grid-cols-[1fr_52px_52px] items-center gap-2">
                        <span>서면결의</span>
                        <span className="text-center">{attendanceSummary.paper}</span>
                        <span className="text-center text-slate-600">{attendanceSummary.paperSubmitted}</span>
                      </div>
                      <div className="grid grid-cols-[1fr_52px_52px] items-center gap-2">
                        <span>우편 제출</span>
                        <span className="text-center">{attendanceSummary.mail}</span>
                        <span className="text-center text-slate-600">{attendanceSummary.mailSubmitted}</span>
                      </div>
                      <div className="grid grid-cols-[1fr_52px_52px] items-center gap-2">
                        <span>전자투표</span>
                        <span className="text-center">{attendanceSummary.electronic}</span>
                        <span className="text-center text-slate-600">{attendanceSummary.electronicSubmitted}</span>
                      </div>
                      <div className="grid grid-cols-[1fr_52px_52px] items-center gap-2">
                        <span className="whitespace-nowrap">온라인 참석</span>
                        <span className="text-center">{displayedOnsite}</span>
                        <span className="text-center text-slate-400">-</span>
                      </div>
                      <div className="grid grid-cols-[1fr_52px_52px] items-center gap-2">
                        <span className="text-sm leading-tight">
                          <span className="block">현장 참석</span>
                          <span className="block text-[10px] text-slate-500">(오프라인)</span>
                        </span>
                        <span className="text-center">{displayedFieldOnsite}</span>
                        <span className="text-center text-slate-400">-</span>
                      </div>
                      <div className="grid grid-cols-[1fr_52px_52px] items-center gap-2 text-amber-600">
                        <span>전체 조합원</span>
                        <span className="text-center">{displayedAttendingTotal}</span>
                        <span className="text-center">{displayedTotal}</span>
                      </div>
                    </div>
                    <div className="mt-3 rounded-2xl border border-slate-200 bg-slate-50 p-3">
                      <p className="text-[11px] text-slate-500">현장 참석(오프라인) 수동 입력</p>
                      <div className="mt-2 flex items-center gap-2">
                        <input
                          type="number"
                          min="0"
                          step="1"
                          value={manualFieldOnsiteInput}
                          disabled={manualFieldOnsiteSaving}
                          onChange={(event) => {
                            setManualFieldOnsiteInput(event.target.value);
                            setManualFieldOnsiteError('');
                          }}
                          onKeyDown={(event) => {
                            if (event.key === 'Enter') {
                              event.preventDefault();
                              handleApplyManualFieldOnsite();
                            }
                          }}
                          placeholder={String(attendanceSummary.fieldOnsite)}
                          className="w-24 rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-amber-400/60"
                        />
                        <button
                          type="button"
                          onClick={handleApplyManualFieldOnsite}
                          disabled={manualFieldOnsiteSaving}
                          className="min-w-[56px] whitespace-nowrap rounded-xl bg-indigo-600 px-4 py-2 text-xs font-semibold text-white hover:bg-indigo-500 disabled:cursor-not-allowed disabled:bg-indigo-300"
                        >
                          {manualFieldOnsiteSaving ? '저장중' : '적용'}
                        </button>
                      </div>
                      {manualFieldOnsiteError ? (
                        <p className="mt-2 text-xs text-rose-600">{manualFieldOnsiteError}</p>
                      ) : null}
                    </div>
                    <div className="mt-3 rounded-2xl border border-slate-200 bg-slate-50 p-3">
                      <p className="text-[11px] text-slate-500">사용자 페이지 버튼 공개 설정</p>
                      <button
                        type="button"
                        onClick={handleAttendanceVisibilityToggle}
                        disabled={attendanceVisibilityLoading}
                        className={`mt-2 w-full rounded-xl px-4 py-2 text-xs font-semibold text-white ${
                          attendanceVisible
                            ? 'bg-emerald-600 hover:bg-emerald-500'
                            : attendanceVisibilityLoading
                              ? 'bg-slate-400'
                              : 'bg-indigo-600 hover:bg-indigo-500'
                        }`}
                      >
                        {attendanceVisibilityLoading
                          ? '처리 중...'
                          : attendanceVisible
                            ? '회의자료 공개'
                            : '참석자 현황 공개'}
                      </button>
                      {attendanceVisibilityError ? (
                        <p className="mt-2 text-xs text-rose-600">{attendanceVisibilityError}</p>
                      ) : null}
                    </div>
                    <div className="mt-3 rounded-2xl border border-slate-200 bg-slate-50 p-3">
                      <p className="text-[11px] text-slate-500">관제 직원</p>
                      <button
                        type="button"
                        onClick={() => setIsExcludedModalOpen(true)}
                        className="mt-2 w-full rounded-xl bg-slate-700 px-4 py-2 text-xs font-semibold text-white hover:bg-slate-600"
                      >
                        관제 직원 관리
                      </button>
                      <p className="mt-2 text-[10px] text-slate-500">
                        등록된 사람은 출석 카운트에서 제외됩니다.
                      </p>
                    </div>
                  </div>
                </div>
              </div>
              <div className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div>
                    <p className="text-xs uppercase tracking-[0.3em] text-slate-400">방송 정보</p>
                    <h2 className="text-xl font-semibold text-slate-900">라이브 설정</h2>
                  </div>
                  <div className="flex flex-wrap items-center gap-2">
                    {metaStatusLabel ? (
                      <span className="rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1 text-xs font-semibold text-emerald-700">
                        {metaStatusLabel}
                      </span>
                    ) : null}
                    <button
                      onClick={() => setIsStreamMetaModalOpen(true)}
                      className="rounded-full border border-slate-300 px-4 py-2 text-xs font-semibold text-slate-700 transition hover:border-slate-400"
                    >
                      정보
                    </button>
                  </div>
                </div>
                <div className="mt-4 overflow-hidden rounded-2xl border border-slate-200 bg-slate-100">
                  <video
                    ref={videoRef}
                    className="h-[520px] w-full bg-black object-cover xl:h-[600px] 2xl:h-[640px]"
                    muted
                    autoPlay
                    controls
                    playsInline
                  />
                </div>
                <div className="mt-4 grid grid-cols-4 gap-2">
                  <button
                    onClick={() => navigateTo('materials')}
                    className="rounded-full bg-indigo-600 px-3 py-2 text-xs font-semibold text-white"
                  >
                    자료 업로드
                  </button>
                  <button
                    onClick={() => setIsAudioModalOpen(true)}
                    className="rounded-full bg-indigo-600 px-3 py-2 text-xs font-semibold text-white"
                  >
                    음성 파일 확인
                  </button>
                  <button
                    onClick={() => navigateTo('roster')}
                    className="rounded-full bg-indigo-600 px-3 py-2 text-xs font-semibold text-white"
                  >
                    명부 업로드
                  </button>
                  <button
                    onClick={handleVoteToggle}
                    disabled={voteOpenLoading}
                    className={`rounded-full px-3 py-2 text-xs font-semibold ${voteOpen
                      ? 'bg-amber-200 text-amber-800 hover:bg-amber-100'
                      : voteOpenLoading
                        ? 'bg-slate-300 text-slate-600'
                        : 'bg-amber-500 text-white hover:bg-amber-400'
                      }`}
                  >
                    {voteOpen ? '투표 시작중' : voteOpenLoading ? '처리 중...' : '투표 시작'}
                  </button>
                </div>
                {voteOpenError ? (
                  <p className="mt-2 text-xs text-rose-600">{voteOpenError}</p>
                ) : null}
              </div>
              <div className="grid gap-4">
                <div className="rounded-3xl border border-slate-200 bg-white p-4 shadow-sm">
                  <div className="flex items-center justify-between border-b border-slate-200 pb-3">
                    <h3 className="text-sm font-semibold text-slate-900">질의현황</h3>
                    <div className="flex items-center gap-2 text-[10px] font-semibold">
                      <span className="rounded-full bg-amber-100 px-2 py-1 text-amber-700">대기 {pending.length}</span>
                      <span className="rounded-full bg-emerald-100 px-2 py-1 text-emerald-700">승인 {approved.length}</span>
                    </div>
                  </div>
                  <div ref={pendingSectionRef} className="mt-3 max-h-[300px] space-y-3 overflow-y-auto pr-1">
                    {!pendingLoaded && loading && questionItems.length === 0 ? (
                      <div className="rounded-2xl border border-slate-200 bg-white p-4 text-xs text-slate-500">
                        Loading pending messages...
                      </div>
                    ) : null}

                    {pendingLoaded && approvedLoaded && questionItems.length === 0 ? (
                      <div className="rounded-2xl border border-slate-200 bg-white p-4 text-xs text-slate-500">
                        No questions yet.
                      </div>
                    ) : null}

                    {questionItems.map((item) => (
                      <div
                        key={`${item.status}-${item.id}`}
                        className={`rounded-2xl border p-3 ${item.status === 'approved' ? 'border-emerald-200 bg-emerald-50' : 'border-amber-200 bg-amber-50'}`}
                      >
                        <div className="flex items-center justify-between gap-2">
                          <div>
                            <p className="text-xs font-semibold text-slate-900">{item.senderName}</p>
                            <p className="text-[10px] text-slate-400">
                              #{item.id}
                              {formatMessageTime(item.createdAtEpochMs) ? ` · ${formatMessageTime(item.createdAtEpochMs)}` : ''}
                            </p>
                          </div>
                          {item.status === 'pending' ? (
                            <div className="flex gap-1">
                              <button
                                onClick={() => updateStatus(item, 'approve')}
                                disabled={messageActionId === item.id}
                                className="rounded-full bg-emerald-500 px-2 py-1 text-[10px] font-semibold text-white"
                              >
                                승인
                              </button>
                              <button
                                onClick={() => updateStatus(item, 'reject')}
                                disabled={messageActionId === item.id}
                                className="rounded-full border border-rose-300 px-2 py-1 text-[10px] text-rose-600"
                              >
                                거절
                              </button>
                              <button
                                onClick={() => deleteMessage(item)}
                                disabled={messageActionId === item.id}
                                className="rounded-full border border-slate-300 px-2 py-1 text-[10px] text-slate-600"
                              >
                                삭제
                              </button>
                            </div>
                          ) : (
                            <div className="flex gap-1">
                              <span className="rounded-full bg-emerald-500/10 px-2 py-1 text-[10px] font-semibold text-emerald-700">
                                승인됨
                              </span>
                              <button
                                onClick={() => deleteMessage(item)}
                                disabled={messageActionId === item.id}
                                className="rounded-full border border-slate-300 px-2 py-1 text-[10px] text-slate-600"
                              >
                                삭제
                              </button>
                            </div>
                          )}
                        </div>
                        <p className="mt-2 text-xs text-slate-700">{item.message}</p>
                      </div>
                    ))}
                  </div>
                </div>

                <div className="rounded-3xl border border-slate-200 bg-white p-4 shadow-sm">
                  <div className="flex items-center justify-between border-b border-slate-200 pb-3">
                    <h3 className="text-sm font-semibold text-slate-900">음성파일 업로드</h3>
                    <span className="rounded-full bg-slate-100 px-2 py-1 text-[10px] font-semibold text-slate-600">
                      {audioFiles.length}개
                    </span>
                  </div>
                  <div className="mt-3 max-h-[300px] space-y-3 overflow-y-auto pr-1">
                    {!audioLoaded && audioLoading && audioFiles.length === 0 ? (
                      <div className="rounded-2xl border border-slate-200 bg-white p-4 text-xs text-slate-500">
                        Loading audio uploads...
                      </div>
                    ) : null}
                    {audioLoaded && !audioLoading && audioFiles.length === 0 ? (
                      <div className="rounded-2xl border border-slate-200 bg-white p-4 text-xs text-slate-500">
                        No audio uploads yet.
                      </div>
                    ) : null}
                    {audioFiles.map((item) => (
                      <div
                        key={item.id}
                        className="rounded-2xl border border-slate-200 bg-slate-50 p-3"
                      >
                        <p className="text-xs font-semibold text-slate-800">
                          {getAudioDisplayName(item)}
                        </p>
                        <p className="mt-1 text-[10px] text-slate-500">
                          {formatBytes(item.sizeBytes)} · {new Date(item.createdAt).toLocaleString()}
                        </p>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            </section>

            {error ? (
              <div className="rounded-3xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-600">
                {error}
              </div>
            ) : null}
          </>
        )}
      </div>
      <input
        ref={pdfInputRef}
        type="file"
        accept="application/pdf"
        disabled={pdfUploading}
        onChange={handlePdfSelected}
        className="hidden"
      />

      <ExcludedPersonsModal
        streamKey={streamKey}
        isOpen={isExcludedModalOpen}
        onClose={() => setIsExcludedModalOpen(false)}
      />

      {isPendingPdfPreviewOpen ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-4">
          <div
            className="absolute inset-0"
            onClick={closePendingPdfPreview}
            aria-hidden="true"
          />
          <div className="relative z-10 w-full max-w-5xl rounded-3xl border border-slate-200 bg-white p-6 shadow-xl">
            <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 pb-4">
              <div className="min-w-0">
                <p className="text-xs uppercase tracking-[0.3em] text-slate-400">PDF 업로드</p>
                <h2 className="truncate text-xl font-semibold text-slate-900">
                  업로드 전 미리보기 · {pendingPdfFile?.name || '파일'}
                </h2>
              </div>
              <button
                type="button"
                onClick={closePendingPdfPreview}
                disabled={pdfUploading}
                className="rounded-full border border-slate-300 px-4 py-2 text-xs text-slate-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                닫기
              </button>
            </div>

            <div className="mt-4">
              <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={() => setPendingPdfPage((prev) => Math.max(1, prev - 1))}
                    disabled={pendingPdfLoading || pendingPdfPage <= 1}
                    className="rounded-full border border-slate-300 px-3 py-1 text-xs text-slate-700 disabled:opacity-50"
                  >
                    이전 페이지
                  </button>
                  <span className="text-xs font-semibold text-slate-600">
                    {pendingPdfPage}
                    {pendingPdfPageCount ? ` / ${pendingPdfPageCount}` : ''} 페이지
                  </span>
                  <button
                    type="button"
                    onClick={() => setPendingPdfPage((prev) => prev + 1)}
                    disabled={
                      pendingPdfLoading ||
                      Boolean(pendingPdfPageCount && pendingPdfPage >= pendingPdfPageCount)
                    }
                    className="rounded-full border border-slate-300 px-3 py-1 text-xs text-slate-700 disabled:opacity-50"
                  >
                    다음 페이지
                  </button>
                </div>
                {pendingPdfLoading ? <span className="text-xs text-slate-500">PDF 로딩 중...</span> : null}
              </div>

              <div className="flex justify-center rounded-2xl border border-slate-200 bg-white p-2">
                <canvas
                  ref={pendingPdfCanvasRef}
                  className="h-auto w-full max-w-full rounded-xl bg-white"
                />
              </div>
              {pendingPdfRendering ? (
                <p className="mt-2 text-center text-xs text-slate-500">페이지 렌더링 중...</p>
              ) : null}
              {pendingPdfError ? (
                <div className="mt-2 rounded-2xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-600">
                  {pendingPdfError}
                </div>
              ) : null}
            </div>

            <div className="mt-4 flex flex-wrap items-center justify-end gap-2">
              <button
                type="button"
                onClick={handlePdfUploadClick}
                disabled={pdfUploading}
                className="rounded-full border border-slate-300 px-4 py-2 text-xs text-slate-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                다른 파일 선택
              </button>
              <button
                type="button"
                onClick={confirmPendingPdfUpload}
                disabled={pdfUploading}
                className="rounded-full bg-amber-500 px-4 py-2 text-xs font-semibold text-white disabled:cursor-not-allowed disabled:bg-amber-300"
              >
                {pdfUploading ? '업로드 중...' : '이 파일 업로드'}
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {isStreamMetaModalOpen ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-4">
          <div
            className="absolute inset-0"
            onClick={() => setIsStreamMetaModalOpen(false)}
            aria-hidden="true"
          />
          <div className="relative z-10 w-full max-w-xl rounded-3xl border border-slate-200 bg-white p-6 shadow-xl">
            <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 pb-4">
              <div>
                <p className="text-xs uppercase tracking-[0.3em] text-slate-400">라이브 설정</p>
                <h2 className="text-xl font-semibold text-slate-900">방송 정보 입력</h2>
              </div>
              <button
                onClick={() => setIsStreamMetaModalOpen(false)}
                className="rounded-full border border-slate-300 px-4 py-2 text-xs text-slate-700"
              >
                닫기
              </button>
            </div>

            <div className="mt-4 grid gap-4">
              <label className="grid gap-2 text-xs text-slate-500">
                사용자 주소
                <input
                  value="https://meeting.example.com/"
                  readOnly
                  className="rounded-2xl border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-900"
                />
              </label>

              <label className="grid gap-2 text-xs text-slate-500">
                방송 제목
                <input
                  value={streamTitle}
                  onChange={(event) => setStreamTitle(event.target.value)}
                  placeholder="라이브 방송 제목"
                  className="rounded-2xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-amber-400/60"
                />
              </label>

              <div className="rounded-2xl border border-slate-200 bg-slate-50 p-4">
                <button
                  type="button"
                  onClick={() => setIsDatePickerOpen((prev) => !prev)}
                  className="flex w-full items-center justify-between text-left text-xs font-semibold text-slate-700"
                >
                  시작 시간 설정
                  <span className="text-[10px] text-slate-400">
                    {isDatePickerOpen ? '닫기' : '열기'}
                  </span>
                </button>

                {isDatePickerOpen ? (
                  <div className="mt-3 grid gap-3">
                    <div className="grid gap-2 sm:grid-cols-3">
                      <label className="grid gap-1 text-[11px] text-slate-500">
                        년도
                        <select
                          value={startYear}
                          onChange={(event) => setStartYear(event.target.value)}
                          className="rounded-2xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-amber-400/60"
                        >
                          <option value="">선택</option>
                          {yearOptions.map((year) => (
                            <option key={year} value={year}>
                              {year}
                            </option>
                          ))}
                        </select>
                      </label>
                      <label className="grid gap-1 text-[11px] text-slate-500">
                        월
                        <select
                          value={startMonth}
                          onChange={(event) => setStartMonth(event.target.value)}
                          className="rounded-2xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-amber-400/60"
                        >
                          <option value="">선택</option>
                          {Array.from({ length: 12 }, (_, index) => String(index + 1).padStart(2, '0')).map(
                            (month) => (
                              <option key={month} value={month}>
                                {month}
                              </option>
                            )
                          )}
                        </select>
                      </label>
                      <label className="grid gap-1 text-[11px] text-slate-500">
                        일
                        <select
                          value={startDay}
                          onChange={(event) => setStartDay(event.target.value)}
                          className="rounded-2xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-amber-400/60"
                        >
                          <option value="">선택</option>
                          {Array.from({ length: 31 }, (_, index) => String(index + 1).padStart(2, '0')).map(
                            (day) => (
                              <option key={day} value={day}>
                                {day}
                              </option>
                            )
                          )}
                        </select>
                      </label>
                    </div>
                    <label className="grid gap-1 text-[11px] text-slate-500">
                      시간
                      <div className="grid grid-cols-2 gap-2">
                        <select
                          value={startHour}
                          onChange={(event) => setStartHour(event.target.value)}
                          className="rounded-2xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-amber-400/60"
                        >
                          <option value="">시</option>
                          {Array.from({ length: 24 }, (_, index) => String(index).padStart(2, '0')).map((hour) => (
                            <option key={hour} value={hour}>
                              {hour}
                            </option>
                          ))}
                        </select>
                        <select
                          value={startMinute}
                          onChange={(event) => setStartMinute(event.target.value)}
                          className="rounded-2xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-amber-400/60"
                        >
                          <option value="">분</option>
                          {Array.from({ length: 60 }, (_, index) => String(index).padStart(2, '0')).map((minute) => (
                            <option key={minute} value={minute}>
                              {minute}
                            </option>
                          ))}
                        </select>
                      </div>
                    </label>
                  </div>
                ) : null}
              </div>

              {metaError ? <span className="text-xs text-rose-600">{metaError}</span> : null}

              <div className="flex flex-wrap items-center gap-2">
                <button
                  onClick={handleSaveStreamMeta}
                  disabled={metaSaving}
                  className="rounded-full bg-amber-500 px-4 py-2 text-xs font-semibold text-white disabled:cursor-not-allowed disabled:opacity-70"
                >
                  {metaSaving ? '저장 중' : '저장'}
                </button>
                <button
                  onClick={() => {
                    setStreamTitle('');
                    setStartYear('');
                    setStartMonth('');
                    setStartDay('');
                    setStartHour('');
                    setStartMinute('');
                    setScheduledStart('');
                  }}
                  className="rounded-full border border-slate-300 px-4 py-2 text-xs text-slate-700"
                >
                  초기화
                </button>
              </div>
            </div>
          </div>
        </div>
      ) : null}

      {previewMaterial ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-4">
          <div
            className="absolute inset-0"
            onClick={closePreviewMaterial}
            aria-hidden="true"
          />
          <div className="relative z-10 w-full max-w-5xl rounded-3xl border border-slate-200 bg-white p-6 shadow-xl">
            <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 pb-4">
              <div className="min-w-0">
                <p className="text-xs uppercase tracking-[0.3em] text-slate-400">회의자료</p>
                <h2 className="truncate text-xl font-semibold text-slate-900">
                  PDF 미리보기 · {previewMaterial.title || '자료'}
                </h2>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                {safeExternalUrl(previewMaterial.url) ? (
                  <a
                    href={safeExternalUrl(previewMaterial.url)}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="rounded-full border border-slate-300 px-4 py-2 text-xs text-slate-700"
                  >
                    새 창
                  </a>
                ) : null}
                <button
                  onClick={closePreviewMaterial}
                  className="rounded-full border border-slate-300 px-4 py-2 text-xs text-slate-700"
                >
                  닫기
                </button>
              </div>
            </div>

            <div className="mt-4">
              {safeExternalUrl(previewMaterial.url) ? (
                <iframe
                  title={`material-preview-${previewMaterial.id}`}
                  src={safeExternalUrl(previewMaterial.url)}
                  className="h-[72vh] min-h-[520px] w-full rounded-2xl border border-slate-200 bg-white"
                />
              ) : (
                <div className="rounded-2xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-600">
                  PDF 주소를 확인할 수 없습니다.
                </div>
              )}
            </div>
          </div>
        </div>
      ) : null}

      {editingMaterial ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-4">
          <div
            className="absolute inset-0"
            onClick={() => setEditingMaterial(null)}
            aria-hidden="true"
          />
          <div className="relative z-10 w-full max-w-xl rounded-3xl border border-slate-200 bg-white p-6 shadow-xl">
            <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 pb-4">
              <div>
                <p className="text-xs uppercase tracking-[0.3em] text-slate-400">회의자료</p>
                <h2 className="text-xl font-semibold text-slate-900">자료 수정</h2>
              </div>
              <button
                onClick={() => setEditingMaterial(null)}
                className="rounded-full border border-slate-300 px-4 py-2 text-xs text-slate-700"
              >
                닫기
              </button>
            </div>

            <div className="mt-4 grid gap-3">
              <label className="grid gap-2 text-xs text-slate-500">
                제목
                <input
                  value={editTitle}
                  onChange={(event) => setEditTitle(event.target.value)}
                  className="rounded-2xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-amber-400/60"
                />
              </label>
              {editingMaterial.type === 'text' ? (
                <label className="grid gap-2 text-xs text-slate-500">
                  내용
                  <textarea
                    value={editBody}
                    onChange={(event) => setEditBody(event.target.value)}
                    rows={6}
                    className="rounded-2xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-amber-400/60"
                  />
                </label>
              ) : null}
              {editError ? <p className="text-xs text-rose-600">{editError}</p> : null}
              <div className="flex flex-wrap items-center gap-2">
                <button
                  onClick={handleSaveEdit}
                  disabled={editSaving}
                  className="rounded-full bg-emerald-500 px-4 py-2 text-xs font-semibold text-white disabled:cursor-not-allowed disabled:opacity-70"
                >
                  {editSaving ? '저장 중' : '저장'}
                </button>
                <button
                  onClick={() => setEditingMaterial(null)}
                  className="rounded-full border border-slate-300 px-4 py-2 text-xs text-slate-700"
                >
                  취소
                </button>
              </div>
            </div>
          </div>
        </div>
      ) : null}

      {isAudioModalOpen ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-4">
          <div
            className="absolute inset-0"
            onClick={() => {
              if (audioPreviewUrl) {
                URL.revokeObjectURL(audioPreviewUrl);
              }
              setAudioPreviewUrl('');
              setAudioPreviewName('');
              setIsAudioModalOpen(false);
            }}
            aria-hidden="true"
          />
          <div className="relative z-10 w-full max-w-3xl rounded-3xl border border-slate-200 bg-white p-6 shadow-xl">
            <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 pb-4">
              <div>
                <p className="text-xs uppercase tracking-[0.3em] text-slate-400">음성파일</p>
                <h2 className="text-xl font-semibold text-slate-900">업로드된 음성 파일</h2>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <button
                  onClick={toggleAllAudio}
                  className="rounded-full border border-slate-300 px-4 py-2 text-xs text-slate-700 transition hover:border-slate-400"
                >
                  {selectedAudioIds.length === audioFiles.length && audioFiles.length > 0
                    ? '전체 해제'
                    : '전체 선택'}
                </button>
                <button
                  onClick={deleteSelectedAudio}
                  disabled={selectedAudioIds.length === 0}
                  className="rounded-full border border-rose-300 px-4 py-2 text-xs text-rose-600 transition hover:border-rose-400 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  선택 삭제
                </button>
                <button
                  onClick={() => {
                    if (audioPreviewUrl) {
                      URL.revokeObjectURL(audioPreviewUrl);
                    }
                    setAudioPreviewUrl('');
                    setAudioPreviewName('');
                    setIsAudioModalOpen(false);
                  }}
                  className="rounded-full border border-slate-300 px-4 py-2 text-xs text-slate-700"
                >
                  닫기
                </button>
              </div>
            </div>

            <div className="mt-4 rounded-2xl border border-slate-200 bg-slate-50 p-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <p className="text-xs uppercase tracking-[0.3em] text-slate-400">미리듣기</p>
                  <p className="text-sm font-semibold text-slate-900">
                    {audioPreviewName || '선택된 음성파일 없음'}
                  </p>
                </div>
                {audioPreviewLoading ? (
                  <span className="text-xs text-slate-500">불러오는 중...</span>
                ) : null}
              </div>
              {audioPreviewUrl ? (
                <audio controls className="mt-3 w-full">
                  <source src={audioPreviewUrl} />
                </audio>
              ) : (
                <div className="mt-3 rounded-xl border border-dashed border-slate-200 bg-white px-3 py-2 text-xs text-slate-500">
                  미리듣기할 파일을 선택하세요.
                </div>
              )}
            </div>

            <div className="mt-4 max-h-[520px] space-y-3 overflow-y-auto pr-1">
              {!audioLoaded && audioLoading && audioFiles.length === 0 ? (
                <div className="rounded-2xl border border-slate-200 bg-white p-4 text-sm text-slate-500">
                  Loading audio uploads...
                </div>
              ) : null}

              {audioLoaded && !audioLoading && audioFiles.length === 0 ? (
                <div className="rounded-2xl border border-slate-200 bg-white p-4 text-sm text-slate-500">
                  No audio uploads yet.
                </div>
              ) : null}

              {audioFiles.map((item) => (
                <div
                  key={item.id}
                  className="flex flex-wrap items-center justify-between gap-4 rounded-2xl border border-slate-200 bg-white p-4"
                >
                  <div className="flex items-center gap-3">
                    <input
                      type="checkbox"
                      checked={selectedAudioIds.includes(item.id)}
                      onChange={() => toggleAudioSelection(item.id)}
                      className="h-4 w-4 rounded border-slate-300 bg-white text-amber-500"
                    />
                    <div>
                      <p className="text-sm font-semibold text-slate-900">
                        {getAudioDisplayName(item)}
                      </p>
                      <p className="text-xs text-slate-400">
                        {formatBytes(item.sizeBytes)} · {new Date(item.createdAt).toLocaleString()}
                      </p>
                    </div>
                  </div>
                  <div className="flex flex-wrap items-center gap-2">
                    <button
                      onClick={() => previewAudio(item)}
                      className="rounded-full border border-slate-300 px-3 py-1 text-xs text-slate-700 transition hover:border-slate-400"
                    >
                      미리듣기
                    </button>
                    <button
                      onClick={() => downloadAudio(item)}
                      className="rounded-full border border-slate-300 px-3 py-1 text-xs text-slate-700 transition hover:border-slate-400"
                    >
                      Download
                    </button>
                    <button
                      onClick={() => deleteAudio(item.id)}
                      className="rounded-full border border-rose-300 px-3 py-1 text-xs text-rose-600 transition hover:border-rose-400"
                    >
                      Delete
                    </button>
                  </div>
                </div>
              ))}

              {audioError ? (
                <div className="rounded-2xl border border-rose-200 bg-rose-50 p-3 text-sm text-rose-600">
                  {audioError}
                </div>
              ) : null}
            </div>
          </div>
        </div>
      ) : null}

      {rosterUploadNotice ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-4">
          <div
            className="absolute inset-0"
            onClick={() => setRosterUploadNotice(null)}
            aria-hidden="true"
          />
          <div className="relative z-10 w-full max-w-md rounded-3xl border border-slate-200 bg-white p-6 shadow-xl">
            <div className="flex items-center justify-between gap-3 border-b border-slate-200 pb-4">
              <div>
                <p className="text-xs uppercase tracking-[0.3em] text-slate-400">명부</p>
                <h2 className="text-lg font-semibold text-slate-900">{rosterUploadNotice.title}</h2>
              </div>
              <button
                type="button"
                onClick={() => setRosterUploadNotice(null)}
                className="rounded-full border border-slate-300 px-3 py-1 text-xs text-slate-700"
              >
                닫기
              </button>
            </div>
            <div className="mt-4 grid gap-2 text-sm text-slate-700">
              <p>{rosterUploadNotice.summary}</p>
              {Array.isArray(rosterUploadNotice.errors) && rosterUploadNotice.errors.length > 0 ? (
                <div className="rounded-2xl border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900">
                  {rosterUploadNotice.errors.map((msg, idx) => (
                    <p key={`${idx}-${msg}`}>{msg}</p>
                  ))}
                </div>
              ) : null}
            </div>
            <div className="mt-4 flex justify-end">
              <button
                type="button"
                onClick={() => setRosterUploadNotice(null)}
                className="rounded-full bg-indigo-600 px-4 py-2 text-xs font-semibold text-white hover:bg-indigo-500"
              >
                확인
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}
