"use client";

import React, { createContext, useContext, useState, useEffect, ReactNode } from "react";
import { jwtDecode } from "jwt-decode";
import { api, setAuthToken, removeAuthToken, getApiError } from "@/lib/api";

interface JwtPayload {
  sub: string;
  username: string;
  role: string;
}

interface User {
  id: string;
  username: string;
  role: string;
}

interface AuthContextType {
  user: User | null;
  isLoading: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
  isAuthenticated: boolean;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

const TOKEN_KEY = "access_token";

function parseJwt(token: string): User | null {
  try {
    const payload = jwtDecode<JwtPayload>(token);
    return {
      id: payload.sub,
      username: payload.username,
      role: payload.role,
    };
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    const token = localStorage.getItem(TOKEN_KEY);

    if (token) {
      const parsedUser = parseJwt(token);
      if (parsedUser) {
        setUser(parsedUser);
      } else {
        removeAuthToken();
      }
    }
    setIsLoading(false);
  }, []);

  const login = async (username: string, password: string) => {
    try {
      const response = await api.login(username, password);
      const token = response.data.access_token;

      setAuthToken(token);
      const userData = parseJwt(token);
      if (userData) {
        setUser(userData);
      }
    } catch (error) {
      const apiError = getApiError(error);
      throw new Error(apiError.message);
    }
  };

  const logout = () => {
    removeAuthToken();
    setUser(null);
  };

  return (
    <AuthContext.Provider
      value={{
        user,
        isLoading,
        login,
        logout,
        isAuthenticated: !!user,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return context;
}