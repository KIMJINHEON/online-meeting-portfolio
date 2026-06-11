import { useEffect, useMemo, useRef, useState } from 'react';

const WEEKDAY_LABELS = ['일', '월', '화', '수', '목', '금', '토'];

const pad2 = (n) => String(n).padStart(2, '0');

const parseValue = (value) => {
  if (!value || typeof value !== 'string') {
    return null;
  }
  const m = value.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/);
  if (!m) {
    return null;
  }
  const [, y, mo, d, h, mi] = m;
  const year = Number(y);
  const month = Number(mo);
  const day = Number(d);
  const hour = Number(h);
  const minute = Number(mi);
  if (
    !Number.isFinite(year) ||
    !Number.isFinite(month) ||
    !Number.isFinite(day) ||
    !Number.isFinite(hour) ||
    !Number.isFinite(minute)
  ) {
    return null;
  }
  return { year, month, day, hour, minute };
};

const composeValue = (parts) => {
  if (!parts) {
    return '';
  }
  return `${parts.year}-${pad2(parts.month)}-${pad2(parts.day)}T${pad2(parts.hour)}:${pad2(parts.minute)}`;
};

const isSameDay = (a, b) =>
  a && b && a.year === b.year && a.month === b.month && a.day === b.day;

const daysInMonth = (year, month) => new Date(year, month, 0).getDate();

export default function DateTimePicker({
  value = '',
  onChange,
  placeholder = 'YYYY-MM-DD HH:mm',
  className = ''
}) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef(null);

  const today = useMemo(() => {
    const now = new Date();
    return { year: now.getFullYear(), month: now.getMonth() + 1, day: now.getDate() };
  }, []);

  const parsed = useMemo(() => parseValue(value), [value]);

  const [viewYear, setViewYear] = useState(parsed?.year || today.year);
  const [viewMonth, setViewMonth] = useState(parsed?.month || today.month);
  const [draft, setDraft] = useState(() => parsed || { ...today, hour: 0, minute: 0 });

  useEffect(() => {
    if (!open) {
      return;
    }
    const next = parseValue(value);
    if (next) {
      setDraft(next);
      setViewYear(next.year);
      setViewMonth(next.month);
    } else {
      setDraft({ ...today, hour: 0, minute: 0 });
      setViewYear(today.year);
      setViewMonth(today.month);
    }
  }, [open, value, today]);

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const onClick = (event) => {
      if (containerRef.current && !containerRef.current.contains(event.target)) {
        setOpen(false);
      }
    };
    const onKey = (event) => {
      if (event.key === 'Escape') {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onClick);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onClick);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const displayValue = useMemo(() => {
    const p = parseValue(value);
    if (!p) {
      return '';
    }
    return `${p.year}-${pad2(p.month)}-${pad2(p.day)} ${pad2(p.hour)}:${pad2(p.minute)}`;
  }, [value]);

  const calendarCells = useMemo(() => {
    const firstWeekday = new Date(viewYear, viewMonth - 1, 1).getDay();
    const total = daysInMonth(viewYear, viewMonth);
    const prevTotal = daysInMonth(viewYear, viewMonth === 1 ? 12 : viewMonth - 1);
    const cells = [];
    for (let i = 0; i < firstWeekday; i++) {
      const day = prevTotal - firstWeekday + 1 + i;
      const prevMonth = viewMonth === 1 ? 12 : viewMonth - 1;
      const prevYear = viewMonth === 1 ? viewYear - 1 : viewYear;
      cells.push({ year: prevYear, month: prevMonth, day, inMonth: false });
    }
    for (let d = 1; d <= total; d++) {
      cells.push({ year: viewYear, month: viewMonth, day: d, inMonth: true });
    }
    while (cells.length < 42) {
      const lastCell = cells[cells.length - 1];
      const nextDay = lastCell.month === viewMonth ? 1 : lastCell.day + 1;
      const nextMonth = lastCell.month === viewMonth ? (viewMonth === 12 ? 1 : viewMonth + 1) : lastCell.month;
      const nextYear = lastCell.month === viewMonth && viewMonth === 12 ? viewYear + 1 : viewYear;
      cells.push({
        year: nextYear,
        month: nextMonth,
        day: lastCell.month === viewMonth ? nextDay : nextDay,
        inMonth: false
      });
    }
    return cells.slice(0, 42);
  }, [viewYear, viewMonth]);

  const goPrevMonth = () => {
    if (viewMonth === 1) {
      setViewYear(viewYear - 1);
      setViewMonth(12);
    } else {
      setViewMonth(viewMonth - 1);
    }
  };

  const goNextMonth = () => {
    if (viewMonth === 12) {
      setViewYear(viewYear + 1);
      setViewMonth(1);
    } else {
      setViewMonth(viewMonth + 1);
    }
  };

  const handleDayClick = (cell) => {
    setDraft((prev) => ({
      ...prev,
      year: cell.year,
      month: cell.month,
      day: cell.day
    }));
    if (cell.year !== viewYear || cell.month !== viewMonth) {
      setViewYear(cell.year);
      setViewMonth(cell.month);
    }
  };

  const setHour = (h) => setDraft((prev) => ({ ...prev, hour: Number(h) }));
  const setMinute = (m) => setDraft((prev) => ({ ...prev, minute: Number(m) }));

  const handleClear = () => {
    onChange?.('');
    setOpen(false);
  };

  const handleApply = () => {
    onChange?.(composeValue(draft));
    setOpen(false);
  };

  return (
    <div ref={containerRef} className={`relative ${className}`}>
      <button
        type="button"
        onClick={() => setOpen((prev) => !prev)}
        className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-left text-sm text-slate-900 outline-none focus:border-amber-400/60"
      >
        {displayValue || <span className="text-slate-400">{placeholder}</span>}
      </button>

      {open ? (
        <div className="absolute left-0 z-50 mt-2 w-[320px] rounded-2xl border border-slate-200 bg-white p-3 shadow-xl">
          <div className="flex items-center justify-between px-1">
            <button
              type="button"
              onClick={goPrevMonth}
              className="rounded-full px-2 py-1 text-slate-500 hover:bg-slate-100"
              aria-label="이전 달"
            >
              ‹
            </button>
            <div className="text-sm font-semibold text-slate-800">
              {viewYear}년 {viewMonth}월
            </div>
            <button
              type="button"
              onClick={goNextMonth}
              className="rounded-full px-2 py-1 text-slate-500 hover:bg-slate-100"
              aria-label="다음 달"
            >
              ›
            </button>
          </div>

          <div className="mt-2 grid grid-cols-7 text-center text-[11px] font-medium text-slate-400">
            {WEEKDAY_LABELS.map((label) => (
              <div key={label} className="py-1">
                {label}
              </div>
            ))}
          </div>

          <div className="mt-1 grid grid-cols-7 gap-1">
            {calendarCells.map((cell, idx) => {
              const selected = isSameDay(cell, draft);
              const isToday = isSameDay(cell, today);
              const baseClass = 'aspect-square rounded-lg text-xs flex items-center justify-center transition';
              const stateClass = selected
                ? 'bg-emerald-500 text-white font-semibold'
                : isToday
                ? 'bg-amber-100 text-amber-700 font-semibold'
                : cell.inMonth
                ? 'text-slate-700 hover:bg-slate-100'
                : 'text-slate-300 hover:bg-slate-50';
              return (
                <button
                  key={`${cell.year}-${cell.month}-${cell.day}-${idx}`}
                  type="button"
                  onClick={() => handleDayClick(cell)}
                  className={`${baseClass} ${stateClass}`}
                >
                  {cell.day}
                </button>
              );
            })}
          </div>

          <div className="mt-3 flex items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2">
            <span className="text-xs text-slate-500">
              {draft.year}-{pad2(draft.month)}-{pad2(draft.day)}
            </span>
            <span className="text-slate-300">|</span>
            <select
              value={pad2(draft.hour)}
              onChange={(event) => setHour(event.target.value)}
              className="rounded-md border border-slate-200 bg-white px-2 py-1 text-sm text-slate-800 outline-none focus:border-amber-400/60"
            >
              {Array.from({ length: 24 }, (_, i) => pad2(i)).map((h) => (
                <option key={h} value={h}>
                  {h}
                </option>
              ))}
            </select>
            <span className="text-slate-400">:</span>
            <select
              value={pad2(draft.minute)}
              onChange={(event) => setMinute(event.target.value)}
              className="rounded-md border border-slate-200 bg-white px-2 py-1 text-sm text-slate-800 outline-none focus:border-amber-400/60"
            >
              {Array.from({ length: 60 }, (_, i) => pad2(i)).map((m) => (
                <option key={m} value={m}>
                  {m}
                </option>
              ))}
            </select>
          </div>

          <div className="mt-3 flex items-center justify-between gap-2">
            <button
              type="button"
              onClick={handleClear}
              className="rounded-lg border border-slate-300 px-4 py-1.5 text-xs text-slate-600 transition hover:border-slate-400"
            >
              비우기
            </button>
            <button
              type="button"
              onClick={handleApply}
              className="rounded-lg bg-emerald-500 px-5 py-1.5 text-xs font-semibold text-white transition hover:bg-emerald-400"
            >
              적용
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
