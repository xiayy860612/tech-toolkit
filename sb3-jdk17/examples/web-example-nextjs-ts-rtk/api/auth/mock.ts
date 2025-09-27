import { http, HttpResponse } from "msw";
import { LOGIN_API, LoginResponse } from ".";

const loginSuccess = () =>
  HttpResponse.json<LoginResponse>(
    {
      accessToken: "fake-jwt-token",
      user: {
        name: "Username",
        avatar:
          "https://avatars.githubusercontent.com/u/8820862?s=400&u=ec25a1c4c267013084c16208a077776d6d99deb2&v=4",
      },
    },
    {
      headers: {
        "Content-Type": "application/json",
        "x-auth-token": "fake-jwt-token",
      },
    }
  );

const loginFailed = () =>
  HttpResponse.json<ErrorResponse>(
    {
      code: 400,
      message: "bad credentials",
    },
    {
      status: 400,
    }
  );

const loginHandler = http.post<never, any, any>(LOGIN_API, async () =>
  loginSuccess()
);

const authApiHandlers = [loginHandler];
export default authApiHandlers;
