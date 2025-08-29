import { ErrorCode } from "@/types/api/response";

export class InnerError extends Error {
  statusCode: number;
  code: ErrorCode;

  constructor(message: string, statusCode: number, code: ErrorCode) {
    super(message);
    this.statusCode = statusCode;
    this.code = code;

    // Set the prototype explicitly for better stack trace handling
    Object.setPrototypeOf(this, new.target.prototype);

    // Ensure the name property is set correctly for CustomError instances
    this.name = this.constructor.name;
  }
}
