export interface ErrorResponse {
  status?: number;
  code?: number | string;
  message: string;
}

export const unAuthorizedError = {
  status: 401,
  message: "Unauthorized",
};
