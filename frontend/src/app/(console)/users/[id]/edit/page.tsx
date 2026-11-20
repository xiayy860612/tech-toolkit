import { headers } from "next/headers";
import { UserEditor } from "@/features/users/UserEditor";
import { getCurrentSession } from "@/server/session/current";

export default async function EditUserPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const me = await getCurrentSession(await headers());
  return (
    <div className="flex flex-col gap-5">
      <h1 className="text-2xl font-semibold">编辑用户</h1>
      <UserEditor id={id} currentUserId={me?.userId} />
    </div>
  );
}
