import { ChangePasswordForm } from '@/components/ChangePasswordForm';
import { requireSession } from '@/server/auth';
import { changePasswordAction } from './actions';

export default async function ChangePasswordPage() {
  const session = await requireSession({ allowMustChangePassword: true });
  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <div className="flex w-full max-w-sm flex-col gap-4">
        <h1 className="text-center text-2xl font-semibold">修改密码</h1>
        {session.mustChangePassword ? (
          <p className="text-sm text-muted-foreground">首次登录需先修改初始密码。</p>
        ) : null}
        <ChangePasswordForm action={changePasswordAction} />
      </div>
    </main>
  );
}
