import { http, HttpResponse } from "msw";
import { LOGIN_API, LoginRequest, LoginResponse } from ".";

const UnauthorizedResponse = new HttpResponse(null, {
  status: 401,
  statusText: "Unauthorized",
});

const loginSuccess = HttpResponse.json<LoginResponse>(
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

const loginFailed = HttpResponse.json<ErrorResponse>(
  {
    code: 400,
    message: "bad credentials",
  },
  {
    status: 400,
  }
);

const loginHandler = http.post<
  never,
  LoginRequest,
  LoginResponse | ErrorResponse
>(LOGIN_API, async () => {
  return loginSuccess;
});

export default loginHandler;
