"use client";
import { UserInfo } from "@/api/user";
import { Avatar } from "antd";

export interface UserCardProps {
  userInfo: UserInfo;
}

export function UserCard({ userInfo }: UserCardProps) {
  return (
    <div className="flex items-center gap-2">
      <Avatar size="large" src={userInfo.avatar} alt={userInfo.name} />
    </div>
  );
}
