import { http, HttpResponse } from "msw";
import { GET_USER_INFO_URL, GetUserInfoResponse } from ".";

const getUserInfoSuccess = () =>
  HttpResponse.json<GetUserInfoResponse>({
    user: {
      name: "Username",
      avatar:
        "https://avatars.githubusercontent.com/u/8820862?s=400&u=ec25a1c4c267013084c16208a077776d6d99deb2&v=4",
    },
  });

const getUserInfoHandler = http.get<never, never, any>(
  GET_USER_INFO_URL,
  async () => getUserInfoSuccess()
);

const userApiHandlers = [getUserInfoHandler];
export default userApiHandlers;
