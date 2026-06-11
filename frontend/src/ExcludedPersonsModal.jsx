import { useCallback, useEffect, useState } from 'react';

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

const errorMessageFor = (err) => {
  if (!err) {
    return '요청 실패';
  }
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
    case 'name_required':
      return '이름을 입력해주세요.';
    case 'invalid_birth':
      return '생년월일은 YYMMDD 6자리로 입력해주세요. (예: 990101)';
    case 'invalid_phone':
      return '전화번호 형식이 올바르지 않습니다. (예: 010-1234-5678)';
    case 'already_registered':
      return '이미 등록된 사람입니다.';
    case 'streamKey_required':
      return '회의가 선택되지 않았습니다.';
    case 'not_found':
      return '대상을 찾을 수 없습니다.';
    default:
      break;
  }
  if (err.status === 401) {
    return '로그인이 필요합니다.';
  }
  if (err.status === 403) {
    return '권한이 없습니다.';
  }
  return '요청 실패';
};

const blankForm = () => ({ name: '', birth: '', phone: '' });

export default function ExcludedPersonsModal({ streamKey, isOpen, onClose }) {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [form, setForm] = useState(blankForm());
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState('');
  const [deletingId, setDeletingId] = useState(null);

  const load = useCallback(async () => {
    if (!streamKey) return;
    setLoading(true);
    setLoadError('');
    try {
      const data = await fetchJson(
        `/api/admin/excluded-persons?streamKey=${encodeURIComponent(streamKey)}`
      );
      setItems(Array.isArray(data) ? data : []);
    } catch (err) {
      setLoadError(errorMessageFor(err));
    } finally {
      setLoading(false);
    }
  }, [streamKey]);

  useEffect(() => {
    if (isOpen) {
      load();
      setForm(blankForm());
      setFormError('');
    }
  }, [isOpen, load]);

  const handleSubmit = async (event) => {
    event.preventDefault();
    setFormError('');
    if (!form.name.trim() || !form.birth.trim() || !form.phone.trim()) {
      setFormError('이름·생년월일·전화번호를 모두 입력해주세요.');
      return;
    }
    setSubmitting(true);
    try {
      await fetchJson('/api/admin/excluded-persons', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          streamKey,
          name: form.name.trim(),
          birth: form.birth.trim(),
          phone: form.phone.trim()
        })
      });
      setForm(blankForm());
      await load();
    } catch (err) {
      setFormError(errorMessageFor(err));
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (person) => {
    if (deletingId !== null) return;
    if (!window.confirm(`"${person.name}" 을(를) 관제 직원 명단에서 삭제할까요?`)) return;
    setDeletingId(person.id);
    try {
      await fetchJson(
        `/api/admin/excluded-persons/${person.id}?streamKey=${encodeURIComponent(streamKey)}`,
        { method: 'DELETE' }
      );
      setItems((prev) => prev.filter((it) => it.id !== person.id));
    } catch (err) {
      window.alert(errorMessageFor(err));
    } finally {
      setDeletingId(null);
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 px-4 py-8">
      <div className="w-full max-w-lg rounded-3xl bg-white p-6 shadow-2xl">
        <div className="flex items-start justify-between">
          <div>
            <p className="text-xs uppercase tracking-[0.3em] text-slate-400">관제 직원</p>
            <h2 className="mt-1 text-xl font-semibold text-slate-900">관제 직원 관리</h2>
            <p className="mt-1 text-xs text-slate-500">
              여기 등록된 사람은 이 회의의 출석 현황 카운트에서 제외됩니다.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-full border border-slate-300 px-3 py-1 text-xs text-slate-600 hover:border-slate-400"
          >
            닫기
          </button>
        </div>

        {/* 추가 폼 */}
        <form onSubmit={handleSubmit} className="mt-5 grid gap-2 rounded-2xl border border-slate-200 bg-slate-50 p-4">
          <p className="text-xs font-semibold text-slate-600">신규 추가</p>
          <input
            value={form.name}
            onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))}
            placeholder="이름 (예: 홍길동)"
            className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm outline-none focus:border-amber-400/60"
          />
          <input
            value={form.birth}
            onChange={(e) => setForm((p) => ({ ...p, birth: e.target.value }))}
            placeholder="생년월일 YYMMDD (예: 990101)"
            maxLength={6}
            className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm outline-none focus:border-amber-400/60"
          />
          <input
            value={form.phone}
            onChange={(e) => setForm((p) => ({ ...p, phone: e.target.value }))}
            placeholder="전화번호 (예: 010-1234-5678)"
            className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm outline-none focus:border-amber-400/60"
          />
          <div className="flex items-center gap-2">
            <button
              type="submit"
              disabled={submitting}
              className="rounded-full bg-amber-500 px-4 py-2 text-xs font-semibold text-white shadow-sm transition hover:bg-amber-400 disabled:opacity-50"
            >
              {submitting ? '추가 중...' : '추가'}
            </button>
            {formError ? <span className="text-xs text-rose-500">{formError}</span> : null}
          </div>
        </form>

        {/* 목록 */}
        <div className="mt-5">
          <div className="flex items-center justify-between">
            <p className="text-xs font-semibold text-slate-600">
              등록된 관제 직원 {items.length}명
            </p>
            <button
              type="button"
              onClick={load}
              disabled={loading}
              className="rounded-full border border-slate-300 px-3 py-1 text-[11px] text-slate-600 hover:border-slate-400 disabled:opacity-50"
            >
              {loading ? '불러오는 중...' : '새로고침'}
            </button>
          </div>

          {loadError ? <p className="mt-2 text-sm text-rose-500">{loadError}</p> : null}

          {!loading && items.length === 0 && !loadError ? (
            <p className="mt-3 text-xs text-slate-500">아직 등록된 관제 직원이 없습니다.</p>
          ) : null}

          <ul className="mt-3 grid gap-2 max-h-72 overflow-y-auto">
            {items.map((person) => (
              <li
                key={person.id}
                className="flex items-center justify-between rounded-xl border border-slate-200 bg-white px-3 py-2"
              >
                <div className="min-w-0">
                  <p className="text-sm font-medium text-slate-900">{person.name}</p>
                  <p className="text-[11px] text-slate-500">
                    {person.birth} · {person.phone}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => handleDelete(person)}
                  disabled={deletingId === person.id}
                  className="rounded-full border border-rose-300 px-3 py-1 text-[11px] text-rose-600 transition hover:border-rose-400 disabled:opacity-50"
                >
                  {deletingId === person.id ? '삭제 중...' : '삭제'}
                </button>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  );
}
