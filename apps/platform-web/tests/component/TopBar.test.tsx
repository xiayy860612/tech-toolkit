import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { TopBar } from '@/components/TopBar';

const user = { displayName: '张三', accountUsername: 'zhangsan', tenantSlug: 'demo' };

describe('TopBar', () => {
  it('is a header with the product name and the user menu', () => {
    render(<TopBar user={user} />);
    const header = screen.getByRole('banner');
    expect(within(header).getByText('Platform')).toBeInTheDocument();
    expect(within(header).getByRole('button', { name: /张三/ })).toBeInTheDocument();
  });

  it('renders the leading slot before the product name', () => {
    render(<TopBar user={user} leading={<button>lead</button>} />);
    const header = screen.getByRole('banner');
    const lead = within(header).getByRole('button', { name: 'lead' });
    const name = within(header).getByText('Platform');
    expect(lead.compareDocumentPosition(name) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});
