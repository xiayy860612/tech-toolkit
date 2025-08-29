import { AxiosResponse } from "axios";

const isoDateRegex = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/;

function convertDates(obj: unknown): unknown {
  if (Array.isArray(obj)) {
    return obj.map(convertDates);
  } else if (obj && typeof obj === "object") {
    return Object.fromEntries(
      Object.entries(obj).map(([key, value]) => [key, convertDates(value)])
    );
  } else if (typeof obj === "string" && isoDateRegex.test(obj)) {
    return new Date(obj);
  }
  return obj;
}

export const responseConverterInterceptor = (response: AxiosResponse) => {
  response.data = convertDates(response.data);
  return response;
};
