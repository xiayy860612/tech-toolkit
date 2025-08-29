import { NextRequest } from "next/server";
import { handleError } from "./middlewares/error-handler.middleware";

type HandlerType<T> = (req: NextRequest) => Promise<T>;

export const withApiHandler = <T>(handler: HandlerType<T>) => {
  return async (req: NextRequest) => {
    try {
      return await handler(req);
    } catch (error) {
      return handleError(req, error);
    }
  };
};
