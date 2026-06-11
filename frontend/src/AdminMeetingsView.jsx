import { useCallback, useEffect, useMemo, useState } from 'react';
import DateTimePicker from './DateTimePicker.jsx';

const fetchJson = async (url, options = {}) => {
  const response = await fetch(url, { credentials: 'include', ...options });
  if (!response.ok) {
    let body = '';
    try {
      body = await response.text();
    } catch (_) {
      // ignore
    }
    const error = new Error(body || `Request failed: ${response.status}`);
    error.status = response.status;
    error.body = body;
    throw error;
  }
  if (response.status === 204) {
    return null;
  }
  return response.json();
};

const formatLocalDateTime = (value) => {
  if (!value) {
    return '';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return String(value);
  }
  const pad = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
};

const toIsoLocal = (value) => {
  if (!value) {
    return '';
  }
  return value.length === 16 ? `${value}:00` : value;
};

const blankForm = () => ({
  title: '',
  startAt: '',
  endAt: '',
  voteUrl: ''
});

const errorMessageFor = (err) => {
  if (!err) {
    return '요청 실패';
  }
  const status = err.status;
  let code = '';
  if (err.body) {
    try {
      const parsed = JSON.parse(err.body);
      code = parsed?.error || '';
    } catch (_) {
      code = '';
    }
  }
  switch (code) {
    case 'title_required':
      return '회의 제목을 입력해주세요.';
    case 'start_at_required':
      return '시작 일시를 입력해주세요.';
    case 'end_at_required':
      return '종료 일시를 입력해주세요.';
    case 'end_at_must_be_after_start_at':
      return '종료 일시는 시작 일시보다 늦어야 합니다.';
    case 'meeting_not_found':
      return '회의를 찾을 수 없습니다.';
    case 'stream_key_required':
      return '스트림 키가 필요합니다.';
    case 'vote_url_invalid_scheme':
      return '투표 URL은 http:// 또는 https:// 로 시작해야 합니다.';
    case 'vote_url_too_long':
      return '투표 URL이 너무 깁니다. (최대 500자)';
    default:
      break;
  }
  if (status === 401) {
    return '로그인이 필요합니다.';
  }
  if (status === 403) {
    return '권한이 없습니다.';
  }
  return code ? `요청 실패 (${code})` : '요청 실패';
};

export default function AdminMeetingsView() {
  const [meetings, setMeetings] = useState([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [actionId, setActionId] = useState(null);
  const [actionError, setActionError] = useState('');
  const [editingId, setEditingId] = useState(null);
  const [editForm, setEditForm] = useState(blankForm());
  const [createForm, setCreateForm] = useState(blankForm());
  const [createError, setCreateError] = useState('');
  const [createSubmitting, setCreateSubmitting] = useState(false);
  const [revealedKeyId, setRevealedKeyId] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    try {
      const data = await fetchJson('/api/admin/meetings');
      setMeetings(Array.isArray(data) ? data : []);
    } catch (err) {
      setLoadError(errorMessageFor(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const handleCreate = async (event) => {
    event.preventDefault();
    setCreateError('');
    const title = createForm.title.trim();
    if (!title) {
      setCreateError('회의 제목을 입력해주세요.');
      return;
    }
    if (!createForm.startAt || !createForm.endAt) {
      setCreateError('시작/종료 일시를 모두 입력해주세요.');
      return;
    }
    setCreateSubmitting(true);
    try {
      await fetchJson('/api/admin/meetings', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          title,
          startAt: toIsoLocal(createForm.startAt),
          endAt: toIsoLocal(createForm.endAt),
          voteUrl: createForm.voteUrl.trim()
        })
      });
      setCreateForm(blankForm());
      await load();
    } catch (err) {
      setCreateError(errorMessageFor(err));
    } finally {
      setCreateSubmitting(false);
    }
  };

  const handleToggleAccess = async (meeting, nextOpen) => {
    if (actionId !== null) {
      return;
    }
    setActionError('');
    setActionId(meeting.id);
    try {
      await fetchJson(`/api/admin/meetings/${meeting.id}/access`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ open: nextOpen })
      });
      await load();
    } catch (err) {
      setActionError(errorMessageFor(err));
    } finally {
      setActionId(null);
    }
  };

  const handleDelete = async (meeting) => {
    if (actionId !== null) {
      return;
    }
    if (!window.confirm(`"${meeting.title}" 회의를 삭제할까요? (Soft delete — 데이터는 유지됩니다)`)) {
      return;
    }
    setActionError('');
    setActionId(meeting.id);
    try {
      await fetchJson(`/api/admin/meetings/${meeting.id}`, { method: 'DELETE' });
      await load();
    } catch (err) {
      setActionError(errorMessageFor(err));
    } finally {
      setActionId(null);
    }
  };

  const beginEdit = (meeting) => {
    setEditingId(meeting.id);
    setEditForm({
      title: meeting.title || '',
      startAt: meeting.startAt ? String(meeting.startAt).slice(0, 16) : '',
      endAt: meeting.endAt ? String(meeting.endAt).slice(0, 16) : '',
      voteUrl: meeting.voteUrl || ''
    });
    setActionError('');
  };

  const cancelEdit = () => {
    setEditingId(null);
    setEditForm(blankForm());
  };

  const handleEditSubmit = async (event) => {
    event.preventDefault();
    if (editingId === null) {
      return;
    }
    setActionError('');
    setActionId(editingId);
    try {
      await fetchJson(`/api/admin/meetings/${editingId}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          title: editForm.title.trim(),
          startAt: toIsoLocal(editForm.startAt),
          endAt: toIsoLocal(editForm.endAt),
          voteUrl: editForm.voteUrl.trim()
        })
      });
      setEditingId(null);
      setEditForm(blankForm());
      await load();
    } catch (err) {
      setActionError(errorMessageFor(err));
    } finally {
      setActionId(null);
    }
  };

  const goToMeeting = (meeting) => {
    window.location.href = `/admin.html?meetingId=${meeting.id}`;
  };

  const totalActive = useMemo(() => meetings.filter((m) => m.accessOpen).length, [meetings]);

  return (
    <div className="relative min-h-screen overflow-hidden bg-slate-50 text-slate-900">
      <div className="pointer-events-none absolute inset-0 bg-gradient-to-br from-white via-slate-50 to-slate-100" />
      <div className="relative z-10 mx-auto flex w-full max-w-6xl flex-col gap-6 px-6 py-8">
        <header className="flex flex-wrap items-center justify-between gap-4">
          <div>
            <p className="text-xs uppercase tracking-[0.4em] text-slate-400">Admin · Meetings</p>
            <h1 className="text-3xl font-semibold text-slate-900">회의 관리</h1>
            <p className="mt-1 text-sm text-slate-500">
              회의 추가/수정/삭제 및 시청자 접속 허용 토글. 현재 활성 회의 {totalActive}개.
            </p>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={async () => {
                try {
                  await fetch('/api/admin/logout', { method: 'POST', credentials: 'include' });
                } catch (_) {
                  // ignore
                }
                window.location.href = '/admin.html';
              }}
              className="rounded-full border border-slate-300 px-4 py-2 text-sm text-slate-700 transition hover:border-slate-400 hover:bg-slate-50"
            >
              로그아웃
            </button>
          </div>
        </header>

        <section className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm">
          <h2 className="text-lg font-semibold text-slate-900">새 회의 추가</h2>
          <p className="mt-1 text-sm text-slate-500">제목 + 시작/종료 일시 입력. 스트림 키는 자동 발급됩니다.</p>
          <form onSubmit={handleCreate} className="mt-4 grid gap-3 sm:grid-cols-2">
            <label className="flex flex-col gap-1 sm:col-span-2">
              <span className="text-xs font-medium text-slate-500">회의 제목</span>
              <input
                value={createForm.title}
                onChange={(e) => setCreateForm((prev) => ({ ...prev, title: e.target.value }))}
                placeholder="예) 1단지 정기총회"
                className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm outline-none focus:border-amber-400/60"
              />
            </label>
            <div className="flex flex-col gap-1">
              <span className="text-xs font-medium text-slate-500">시작 일시</span>
              <DateTimePicker
                value={createForm.startAt}
                onChange={(next) => setCreateForm((prev) => ({ ...prev, startAt: next }))}
              />
            </div>
            <div className="flex flex-col gap-1">
              <span className="text-xs font-medium text-slate-500">종료 일시</span>
              <DateTimePicker
                value={createForm.endAt}
                onChange={(next) => setCreateForm((prev) => ({ ...prev, endAt: next }))}
              />
            </div>
            <label className="flex flex-col gap-1 sm:col-span-2">
              <span className="text-xs font-medium text-slate-500">투표 URL (선택)</span>
              <input
                value={createForm.voteUrl}
                onChange={(e) => setCreateForm((prev) => ({ ...prev, voteUrl: e.target.value }))}
                placeholder="예) https://www.example.com/vote/?code=225"
                className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm outline-none focus:border-amber-400/60"
              />
              <span className="text-[11px] text-slate-400">
                시청자가 "투표하기" 버튼을 눌렀을 때 열리는 페이지입니다. 비워두면 투표 버튼이 안내 모달만 띄웁니다.
              </span>
            </label>
            <div className="sm:col-span-2 flex items-center gap-3">
              <button
                type="submit"
                disabled={createSubmitting}
                className="rounded-full bg-amber-500 px-5 py-2 text-sm font-semibold text-white transition hover:bg-amber-400 disabled:opacity-50"
              >
                {createSubmitting ? '생성 중...' : '회의 추가'}
              </button>
              {createError ? <p className="text-sm text-rose-500">{createError}</p> : null}
            </div>
          </form>
        </section>

        <section className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h2 className="text-lg font-semibold text-slate-900">회의 목록</h2>
            <button
              onClick={load}
              disabled={loading}
              className="rounded-full border border-slate-300 px-4 py-1.5 text-xs text-slate-700 transition hover:border-slate-400 disabled:opacity-50"
            >
              {loading ? '불러오는 중...' : '새로고침'}
            </button>
          </div>

          {loadError ? <p className="mt-3 text-sm text-rose-500">{loadError}</p> : null}
          {actionError ? <p className="mt-3 text-sm text-rose-500">{actionError}</p> : null}

          {!loading && meetings.length === 0 && !loadError ? (
            <p className="mt-6 text-sm text-slate-500">등록된 회의가 없습니다. 위에서 새 회의를 추가해주세요.</p>
          ) : null}

          <ul className="mt-4 grid gap-3">
            {meetings.map((meeting) => {
              const isEditing = editingId === meeting.id;
              const isActing = actionId === meeting.id;
              const revealed = revealedKeyId === meeting.id;
              return (
                <li
                  key={meeting.id}
                  className="rounded-2xl border border-slate-200 bg-slate-50/60 p-4"
                >
                  {isEditing ? (
                    <form onSubmit={handleEditSubmit} className="grid gap-3 sm:grid-cols-2">
                      <label className="flex flex-col gap-1 sm:col-span-2">
                        <span className="text-xs font-medium text-slate-500">회의 제목</span>
                        <input
                          value={editForm.title}
                          onChange={(e) => setEditForm((prev) => ({ ...prev, title: e.target.value }))}
                          className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm outline-none focus:border-amber-400/60"
                        />
                      </label>
                      <div className="flex flex-col gap-1">
                        <span className="text-xs font-medium text-slate-500">시작 일시</span>
                        <DateTimePicker
                          value={editForm.startAt}
                          onChange={(next) => setEditForm((prev) => ({ ...prev, startAt: next }))}
                        />
                      </div>
                      <div className="flex flex-col gap-1">
                        <span className="text-xs font-medium text-slate-500">종료 일시</span>
                        <DateTimePicker
                          value={editForm.endAt}
                          onChange={(next) => setEditForm((prev) => ({ ...prev, endAt: next }))}
                        />
                      </div>
                      <label className="flex flex-col gap-1 sm:col-span-2">
                        <span className="text-xs font-medium text-slate-500">투표 URL (선택)</span>
                        <input
                          value={editForm.voteUrl}
                          onChange={(e) => setEditForm((prev) => ({ ...prev, voteUrl: e.target.value }))}
                          placeholder="예) https://www.example.com/vote/?code=225"
                          className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm outline-none focus:border-amber-400/60"
                        />
                      </label>
                      <div className="sm:col-span-2 flex items-center gap-2">
                        <button
                          type="submit"
                          disabled={isActing}
                          className="rounded-full bg-amber-500 px-4 py-1.5 text-xs font-semibold text-white transition hover:bg-amber-400 disabled:opacity-50"
                        >
                          {isActing ? '저장 중...' : '저장'}
                        </button>
                        <button
                          type="button"
                          onClick={cancelEdit}
                          className="rounded-full border border-slate-300 px-4 py-1.5 text-xs text-slate-700 transition hover:border-slate-400"
                        >
                          취소
                        </button>
                      </div>
                    </form>
                  ) : (
                    <div className="flex flex-wrap items-start justify-between gap-4">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2">
                          <h3 className="truncate text-base font-semibold text-slate-900">{meeting.title}</h3>
                          {meeting.accessOpen ? (
                            <span className="rounded-full bg-emerald-100 px-2 py-0.5 text-xs font-medium text-emerald-700">
                              접속 허용 중
                            </span>
                          ) : (
                            <span className="rounded-full bg-slate-200 px-2 py-0.5 text-xs font-medium text-slate-600">
                              접속 차단 중
                            </span>
                          )}
                        </div>
                        <p className="mt-1 text-xs text-slate-500">
                          {formatLocalDateTime(meeting.startAt)} ~ {formatLocalDateTime(meeting.endAt)}
                        </p>
                        {meeting.voteUrl ? (
                          <p className="mt-2 truncate text-xs text-slate-500">
                            <span className="font-medium text-slate-600">투표 URL:</span>{' '}
                            <span className="font-mono">{meeting.voteUrl}</span>
                          </p>
                        ) : (
                          <p className="mt-2 text-xs text-slate-400">
                            <span className="font-medium">투표 URL:</span> 미설정 (시청자 투표 버튼 비활성 안내)
                          </p>
                        )}
                        <p className="mt-2 text-xs text-slate-500">
                          <span className="font-medium text-slate-600">스트림 키:</span>{' '}
                          <span className="font-mono">
                            {revealed ? meeting.streamKey : '•'.repeat(Math.min(meeting.streamKey.length, 18))}
                          </span>
                          <button
                            type="button"
                            onClick={() => setRevealedKeyId(revealed ? null : meeting.id)}
                            className="ml-2 rounded-full border border-slate-300 px-2 py-0.5 text-[10px] text-slate-600 transition hover:border-slate-400"
                          >
                            {revealed ? '숨기기' : '보기'}
                          </button>
                          {revealed ? (
                            <button
                              type="button"
                              onClick={() => navigator.clipboard?.writeText(meeting.streamKey)}
                              className="ml-1 rounded-full border border-slate-300 px-2 py-0.5 text-[10px] text-slate-600 transition hover:border-slate-400"
                            >
                              복사
                            </button>
                          ) : null}
                        </p>
                      </div>
                      <div className="flex flex-wrap items-center gap-2">
                        <button
                          onClick={() => handleToggleAccess(meeting, !meeting.accessOpen)}
                          disabled={isActing}
                          className={
                            meeting.accessOpen
                              ? 'rounded-full bg-rose-500 px-4 py-1.5 text-xs font-semibold text-white transition hover:bg-rose-400 disabled:opacity-50'
                              : 'rounded-full bg-emerald-500 px-4 py-1.5 text-xs font-semibold text-white transition hover:bg-emerald-400 disabled:opacity-50'
                          }
                        >
                          {meeting.accessOpen ? '접속 차단' : '접속 허용'}
                        </button>
                        <button
                          onClick={() => goToMeeting(meeting)}
                          className="rounded-full border border-slate-300 px-4 py-1.5 text-xs text-slate-700 transition hover:border-slate-400"
                        >
                          관리 페이지 열기
                        </button>
                        <button
                          onClick={() => beginEdit(meeting)}
                          className="rounded-full border border-slate-300 px-4 py-1.5 text-xs text-slate-700 transition hover:border-slate-400"
                        >
                          수정
                        </button>
                        <button
                          onClick={() => handleDelete(meeting)}
                          disabled={isActing}
                          className="rounded-full border border-rose-300 px-4 py-1.5 text-xs text-rose-600 transition hover:border-rose-400 disabled:opacity-50"
                        >
                          삭제
                        </button>
                      </div>
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        </section>

        <p className="text-center text-xs text-slate-400">
          회의를 클릭하면 해당 회의의 관리 페이지로 이동합니다.
        </p>
      </div>
    </div>
  );
}
