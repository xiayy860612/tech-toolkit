import { AuthLayout } from "@/components/AuthLayout";
import { LoginForm } from "@/features/auth/LoginForm";

export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ next?: string | string[] }>;
}) {
  const { next } = await searchParams;
  return (
    <AuthLayout>
      <LoginForm next={typeof next === "string" ? next : undefined} />
    </AuthLayout>
  );
}
