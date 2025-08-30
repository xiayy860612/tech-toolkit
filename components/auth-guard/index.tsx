"use client";

import { ErrorResponse } from "@/api";
import { getUserInfo } from "@/api/auth/getUserInfo";
import { useAppDispatch } from "@/store";
import { setUserAction } from "@/store/user";
import { useRouter } from "next/navigation";
import { useEffect } from "react";

export function withAuthGuard<P extends object>(
  WrappedComponent: React.ComponentType<P>
): React.FC<P> {
  const AuthGuard: React.FC<P> = (props) => {
    const router = useRouter();
    const dispatch = useAppDispatch();

    // 执行校验
    useEffect(() => {
      const validate = async () => {
        try {
          const user = await getUserInfo();
          dispatch(setUserAction(user));
          // TODO: check permission
        } catch (error: ErrorResponse) {
          if (error.status === 401) {
            router.push("/login");
          }
        }
      };

      validate();
    }, [dispatch, router]);

    return <WrappedComponent {...props} />;
  };

  return AuthGuard;
}
