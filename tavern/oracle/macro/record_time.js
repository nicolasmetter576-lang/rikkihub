// 时间宏：固定时钟下的 date/time/weekday/iso/datetimeformat + UTC 偏移 + timeDiff + idleDuration
await st.reset();
st.freezeClock('2025-03-04T05:06:07Z');
try {
  const cases = [
    { name: 'time-local', input: '{{time}}' },
    { name: 'time-utc-offset', input: '{{time::UTC+2}}|{{time::UTC-7}}|{{time_UTC+9}}' },
    { name: 'time-bad-offset', input: '{{time::garbage}}' },
    { name: 'date', input: '{{date}}' },
    { name: 'weekday', input: '{{weekday}}' },
    { name: 'isotime', input: '{{isotime}}' },
    { name: 'isodate', input: '{{isodate}}' },
    { name: 'datetimeformat-basic', input: '{{datetimeformat::YYYY-MM-DD HH:mm:ss}}' },
    { name: 'datetimeformat-mixed', input: '{{datetimeformat::dddd, MMMM D YYYY h:mm A}}' },
    { name: 'datetimeformat-lt-ll', input: '{{datetimeformat::LT}}|{{datetimeformat::LL}}|{{datetimeformat::LLLL}}' },
    { name: 'timeDiff-future', input: '{{timeDiff::2025-03-11 05:06:07::2025-03-04 05:06:07}}' },
    { name: 'timeDiff-past', input: '{{timeDiff::2025-03-04 05:06:07::2025-03-11 05:06:07}}' },
    { name: 'idleDuration-days', input: '{{idleDuration}}',
      chat: [
        { name: 'Seraphina', text: 'g', sendDateMillis: 1740880000000 },
        { name: 'User', text: 'hi', isUser: true, sendDateMillis: 1740880100000 },
        { name: 'Seraphina', text: 'yo', sendDateMillis: 1741056367000 },
      ] },
    { name: 'idleDuration-empty', input: '{{idleDuration}}', chat: [] },
    { name: 'idleDuration-only-bot', input: '{{idleDuration}}',
      chat: [{ name: 'Seraphina', text: 'g', sendDateMillis: 1741056367000 }] },
    { name: 'idleDuration-alias', input: '{{idle_duration}}',
      chat: [
        { name: 'User', text: 'u1', isUser: true, sendDateMillis: 1741000000000 },
        { name: 'Seraphina', text: 'a1', sendDateMillis: 1741056367000 },
      ] },
  ];
  return { cases: await runMacroCases(cases) };
} finally {
  st.thawClock();
}
