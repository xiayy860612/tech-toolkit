import { LoginResponse } from "@/api/auth/basicLogin";
import { NextRequest, NextResponse } from "next/server";

export async function POST(req: NextRequest, res: NextResponse) {
  const user = req.headers.get("x-user") as string;
  return NextResponse.json<LoginResponse>({
    accessToken: "fake-jwt-token",
    user: {
      name: user,
      avatar:
        "https://avatars.githubusercontent.com/u/8820862?s=400&u=ec25a1c4c267013084c16208a077776d6d99deb2&v=4",
    },
  });
}
