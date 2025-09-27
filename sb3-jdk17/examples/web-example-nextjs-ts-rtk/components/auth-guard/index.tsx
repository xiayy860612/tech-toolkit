"use client";

import { userApi } from "@/api/user";
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

    const hasAccess = async () => {
      try {
        const response = await dispatch(
          userApi.endpoints.getUserInfo.initiate(undefined, {
            forceRefetch: true,
          })
        ).unwrap();
        dispatch(setUserAction(response.user));
        // TODO: check permission
        return true;
      } catch (error: ErrorResponse | any) {
        if (error.status === 401) {
          router.push("/login");
        }
        return false;
      }
    };

    // 执行校验
    useEffect(() => {
      const validate = async () => {
        await hasAccess();
      };

      validate();
    }, [router]);

    return <WrappedComponent {...props} />;
  };

  return AuthGuard;
}
