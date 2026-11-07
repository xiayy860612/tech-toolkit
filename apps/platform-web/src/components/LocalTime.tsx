'use client';

import { useEffect, useState } from 'react';
import { formatDateTime, formatLocalDateTime } from '@/lib/format';

interface LocalTimeProps {
  iso: string;
  /** Test seam; defaults to the browser's time zone. */
  timeZone?: string;
}

/** Server render and first client render show stable UTC text; the browser's zone replaces it after mount. */
export function LocalTime({ iso, timeZone }: LocalTimeProps) {
  const [text, setText] = useState(() => formatDateTime(iso));
  useEffect(() => {
    setText(formatLocalDateTime(iso, timeZone));
  }, [iso, timeZone]);
  return <time dateTime={iso}>{text}</time>;
}
