import { Badge } from '@/components/ui/badge';
import { statusLabel } from '@/lib/format';

const VARIANTS: Record<string, 'secondary' | 'outline' | 'destructive'> = {
  ACTIVE: 'secondary',
  SUBSCRIBED: 'secondary',
  SUSPENDED: 'outline',
  DISABLED: 'destructive',
};

export function StatusBadge({ status }: { status: string }) {
  return <Badge variant={VARIANTS[status] ?? 'outline'}>{statusLabel(status)}</Badge>;
}
