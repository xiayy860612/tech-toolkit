import { ChangePasswordForm } from '@/components/ChangePasswordForm';
import { requireSession } from '@/server/auth';
import { changePasswordAction } from './actions';

export default async function ChangePasswordPage() {
  const session = await requireSession({ allowMustChangePassword: true });
  return (
    <main className="center">
      <h1>修改密码</h1>
      {session.mustChangePassword ? <p>首次登录需先修改初始密码。</p> : null}
      <ChangePasswordForm action={changePasswordAction} />
    </main>
  );
}
