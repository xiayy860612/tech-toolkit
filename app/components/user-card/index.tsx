import { UserInfo } from "@/api/user";
import { Avatar, Button } from "antd";
import Link from "next/link";

export interface UserCardProps {
  userInfo: UserInfo | null;
}

export function UserCard({ userInfo }: UserCardProps) {
  if (userInfo) {
    return (
      <div className="flex items-center gap-2">
        <Avatar size="large" src={userInfo.avatar} alt={userInfo.name} />
      </div>
    );
  }

  return (
    <Button type="primary">
      <Link href="/login" passHref>
        Login
      </Link>
    </Button>
  );
}
