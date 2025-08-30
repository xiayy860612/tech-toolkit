import { unAuthorizedError } from "@/api";
import { NextRequest, NextResponse } from "next/server";

const providers: AuthenticationProvider[] = [
  basicAuthProvider,
  bearerTokenAuthProvider,
];

export async function authMiddleware(req: NextRequest) {
  const auth = req.headers.get("authorization");
  console.log(auth);
  if (!auth) {
    return NextResponse.json(unAuthorizedError, {
      status: unAuthorizedError.status,
    });
  }

  for (const provider of providers) {
    const user = provider(req);
    if (!user) {
      continue;
    }

    const headers = new Headers(req.headers);
    headers.set("x-user", user);
    return NextResponse.next({ request: { headers } });
  }

  return NextResponse.json(unAuthorizedError, {
    status: unAuthorizedError.status,
  });
}

type AuthenticationProvider = (req: NextRequest) => string | undefined;

function basicAuthProvider(req: NextRequest) {
  const auth = req.headers.get("authorization");
  if (!auth?.startsWith("Basic ")) {
    return undefined;
  }

  const token = auth.substring(6);
  const credentials = atob(token);
  const [username, password] = credentials.split(":");
  return username;
}

function bearerTokenAuthProvider(req: NextRequest) {
  const auth = req.headers.get("authorization");
  if (!auth?.startsWith("Bearer ")) {
    return undefined;
  }

  const token = auth.substring(7);
  return token;
}
