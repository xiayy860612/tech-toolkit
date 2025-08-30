"use client";

const BEARER_TOKEN_KEY = "access_token";

export const saveToken = (token: string) => {
  localStorage.setItem(BEARER_TOKEN_KEY, token);
};

export const clearToken = () => {
  localStorage.removeItem(BEARER_TOKEN_KEY);
};

export const getToken = () => {
  return localStorage.getItem(BEARER_TOKEN_KEY);
};
