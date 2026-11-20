import Link from "next/link";
import { UsersTable } from "@/features/users/UsersTable";

export default function UsersPage() {
  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-semibold">用户管理</h1>
        <Link
          href="/users/new"
          className="inline-flex min-h-10 items-center rounded-control bg-accent px-4 text-sm font-medium text-white hover:bg-accent-hover"
        >
          新建用户
        </Link>
      </div>
      <UsersTable />
    </div>
  );
}
