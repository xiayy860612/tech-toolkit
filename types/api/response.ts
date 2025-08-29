export enum ErrorCode {
  UNKNOWN = 1,
  NO_AHTU = 2,
  INVALID_ID_TOKEN = 3,
  ID_TOKEN_EXPIRED = 4,

  INVALID_PARAM = 11,
  INVALID_CREDENTIALS = 12,
}

export interface ErrorResponse {
  code: ErrorCode | string;
  message: string;
}
