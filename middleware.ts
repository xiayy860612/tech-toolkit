import { NextResponse, type NextRequest } from "next/server";

export const config = {
  matcher: "/api/:function*",
};

export async function middleware(req: NextRequest) {
  return await NextResponse.next();
}
