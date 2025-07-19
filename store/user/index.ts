import { UserInfo } from "@/api/user";
import { isServer } from "@/utils/env-utils";
import { createSlice, PayloadAction } from "@reduxjs/toolkit";
import { RootState } from "..";

const USER_KEY = "user";

interface UserState {
  user: UserInfo | null;
}

const initialState = (): UserState => {
  if (isServer()) {
    return {
      user: null,
    };
  }

  return {
    user: JSON.parse(localStorage.getItem(USER_KEY) || "null"),
  };
};

const slice = createSlice({
  name: "user",
  initialState,
  reducers: {
    setUserAction: (state, action: PayloadAction<UserInfo>) => {
      state.user = action.payload;
      localStorage.setItem(USER_KEY, JSON.stringify(action.payload));
    },
  },
});

export const { setUserAction } = slice.actions;
export const userReducer = slice.reducer;
export const selectUser = (state: RootState) => state.user;
