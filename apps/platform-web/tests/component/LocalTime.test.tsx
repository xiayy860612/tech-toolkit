import { render, screen } from '@testing-library/react';
import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { LocalTime } from '@/components/LocalTime';

const ISO = '2030-01-02T03:04:05Z';

describe('LocalTime', () => {
  it('renders a time element with the ISO dateTime', () => {
    render(<LocalTime iso={ISO} />);
    expect(document.querySelector('time')).toHaveAttribute('datetime', ISO);
  });

  it('server-renders stable UTC text', () => {
    expect(renderToString(<LocalTime iso={ISO} />)).toContain('2030-01-02 03:04 UTC');
  });

  it('replaces the text with local time after mount', () => {
    render(<LocalTime iso={ISO} timeZone="Asia/Shanghai" />);
    expect(screen.getByText('2030-01-02 11:04')).toBeInTheDocument();
    expect(screen.queryByText(/UTC/)).not.toBeInTheDocument();
  });
});
