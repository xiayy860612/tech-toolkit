import { ErrorCode, ErrorResponse } from "@/types/api/response";
import { NextRequest, NextResponse } from "next/server";
import { ZodError } from "zod";
import { InnerError } from "./inner-error";

export function handleError(req: NextRequest, error: unknown) {
  console.error(
    `Request[${req.method} ${req.url}] error: ${error instanceof ZodError}`,
    error
  );
  if (error instanceof ZodError) {
    return handleZodError(error);
  }

  if (error instanceof InnerError) {
    return handleInnerError(error);
  }

  const message =
    error instanceof Error ? error.message : "An unexpected error occurred";
  return handleUnknownError(message);
}

function handleUnknownError(message: string) {
  return NextResponse.json<ErrorResponse>(
    {
      code: ErrorCode.UNKNOWN,
      message,
    },
    { status: 500 }
  );
}

function handleZodError(error: ZodError) {
  return NextResponse.json<ErrorResponse>(
    {
      code: ErrorCode.INVALID_PARAM,
      message: error.errors[0].message,
    },
    { status: 400 }
  );
}

function handleInnerError(error: InnerError) {
  return NextResponse.json<ErrorResponse>(
    {
      code: error.code,
      message: error.message,
    },
    { status: error.statusCode }
  );
}
