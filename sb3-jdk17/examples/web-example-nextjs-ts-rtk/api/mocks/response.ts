import { HttpResponse } from "msw";

export const unauthorizedResponse = () =>
  new HttpResponse(null, {
    status: 401,
    statusText: "Unauthorized",
  });
