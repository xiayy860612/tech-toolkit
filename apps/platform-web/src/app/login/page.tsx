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
    <main className="flex min-h-screen items-center justify-center p-4">
      <div className="flex w-full max-w-sm flex-col gap-4">
        <h1 className="text-center text-2xl font-semibold">登录</h1>
        <LoginForm action={loginAction} next={safeNextPath(requested)} />
      </div>
    </main>
  );
}
