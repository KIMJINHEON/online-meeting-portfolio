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

/**
 * Shown only when a viewer is rostered for 2+ meetings after NICE auth.
 * Lists meetings (title + time only — per the user's design call) so they pick which one to enter.
 */
export default function ViewerMeetingSelector({ meetings, onSelect, disabled = false }) {
  return (
    <div className="relative min-h-screen overflow-hidden bg-[#f7f4ef] text-slate-900">
      <div className="pointer-events-none absolute inset-0">
        <div className="absolute -left-40 top-16 h-80 w-80 rounded-full bg-amber-200/40 blur-3xl" />
        <div className="absolute right-0 top-0 h-[26rem] w-[26rem] rounded-full bg-sky-200/40 lg:blur-[120px]" />
        <div className="absolute bottom-0 left-1/3 h-72 w-72 rounded-full bg-emerald-200/30 lg:blur-[120px]" />
      </div>
      <div className="relative z-10 mx-auto flex min-h-screen w-full max-w-md flex-col items-center justify-center px-6 py-10">
        <div className="w-full rounded-3xl border border-white/60 bg-white/95 p-8 shadow-2xl ring-1 ring-black/5">
          <p className="text-[10px] uppercase tracking-[0.3em] text-slate-500">회의 선택</p>
          <h2 className="mt-2 text-2xl font-semibold text-slate-900">참여할 회의를 선택하세요</h2>
          <p className="mt-2 text-sm text-slate-500">
            본인이 명부에 포함된 회의가 {meetings?.length || 0}개 있습니다.
          </p>
          <ul className="mt-6 grid gap-3">
            {(meetings || []).map((meeting) => (
              <li key={meeting.id || meeting.streamKey}>
                <button
                  type="button"
                  disabled={disabled}
                  onClick={() => onSelect?.(meeting)}
                  className="flex w-full flex-col items-start gap-1 rounded-2xl border border-slate-200 bg-white px-4 py-4 text-left transition hover:border-emerald-400 hover:bg-emerald-50 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  <span className="text-base font-semibold text-slate-900">{meeting.title}</span>
                  <span className="text-xs text-slate-500">
                    {formatLocalDateTime(meeting.startAt)} ~ {formatLocalDateTime(meeting.endAt)}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  );
}
