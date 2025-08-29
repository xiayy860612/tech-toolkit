"use client";

import { LOGIN_ROUTE_PATH } from "@/store/auth";
import { ErrorCode, ErrorResponse } from "@/types/api/response";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";

export function withAuthGuard<P extends object>(
  WrappedComponent: React.ComponentType<P>,
  fallback?: () => React.ReactNode
): React.FC<P> {
  const AuthGuard: React.FC<P> = (props) => {
    const router = useRouter();
    // const { user } = useUserSelector();
    const [valid, setValid] = useState(false);
    const fallbackRender = fallback ?? (() => <>Invalid</>);

    const hasAccess = useCallback(async () => {
      try {
        // TODO: check user permission
        return true;
      } catch (err) {
        const error = err as ErrorResponse;
        if (error.code === ErrorCode.INVALID_CREDENTIALS) {
          router.push(LOGIN_ROUTE_PATH);
        }
        return false;
      }
    }, [router]);

    // 执行校验
    useEffect(() => {
      const validate = async () => {
        const result = await hasAccess();
        setValid(result);
      };

      validate();
    }, [hasAccess, router]);

    if (!valid) {
      return fallbackRender();
    }

    return <WrappedComponent {...props} />;
  };

  return AuthGuard;
}
