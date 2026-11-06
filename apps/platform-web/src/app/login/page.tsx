import { LoginForm } from '@/components/LoginForm';
import { safeNextPath } from '@/lib/safeNextPath';
import { loginAction } from './actions';

export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { next } = await searchParams;
  const requested = Array.isArray(next) ? next[0] : next;
  return (
    <main className="center">
      <h1>登录</h1>
      <LoginForm action={loginAction} next={safeNextPath(requested)} />
    </main>
  );
}
