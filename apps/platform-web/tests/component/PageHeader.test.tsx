import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PageHeader } from '@/components/PageHeader';

describe('PageHeader', () => {
  it('renders the title as the page heading', () => {
    render(<PageHeader title="租户管理" />);
    expect(screen.getByRole('heading', { level: 1, name: '租户管理' })).toBeInTheDocument();
  });

  it('renders no actions container when there are no actions', () => {
    const { container } = render(<PageHeader title="租户管理" />);
    expect(container.querySelector('[data-slot="page-header-actions"]')).toBeNull();
  });

  it('renders the actions slot on the right when given', () => {
    const { container } = render(<PageHeader title="租户管理" actions={<button>新建</button>} />);
    expect(container.querySelector('[data-slot="page-header-actions"]')?.textContent).toBe('新建');
  });
});
