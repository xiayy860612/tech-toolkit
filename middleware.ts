import { NextRequest, NextResponse } from "next/server";
import { authMiddleware } from "./middlewares/auth.middleware";

export const config = {
  matcher: ["/api/:path*"],
};

export type Middleware = (
  req: NextRequest
) => Promise<NextResponse | void> | NextResponse | void;

const filterChains = (middlewares: Middleware[]) => {
  return async (req: NextRequest) => {
    for (const filter of middlewares) {
      const res = await filter(req);
      if (res) {
        // ❌ Stop the chain if middleware returns a response (e.g., 401)
        return res;
      }
    }
    return NextResponse.next();
  };
};

const middleware = filterChains([authMiddleware]);
export default middleware;
