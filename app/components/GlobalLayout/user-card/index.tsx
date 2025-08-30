"use client";

import { UserInfo } from "@/types/model/user";
import { Avatar } from "antd";

export interface UserCardProps {
  user: UserInfo;
}

export function UserCard({ user: userInfo }: UserCardProps) {
  return (
    <div className="flex items-center gap-2">
      <Avatar size="large" src={userInfo.avatar} alt={userInfo.name} />
    </div>
  );
}
