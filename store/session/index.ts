import { createSlice, PayloadAction } from "@reduxjs/toolkit";
import { RootState, useAppSelector } from "..";
import { MenuInfo, UserInfo } from "./model";

interface SessionState {
  user: UserInfo | null;
  menus: MenuInfo[];
}

const initialState: SessionState = {
  user: null,
  menus: [],
};

const slice = createSlice({
  name: "session",
  initialState,
  reducers: {
    setUserAction: (state, action: PayloadAction<UserInfo | null>) => {
      state.user = action.payload;
    },
    clearUserAction: (state) => {
      state.user = null;
    },
  },
});

export const { setUserAction, clearUserAction } = slice.actions;
export const sessionReducer = slice.reducer;
export const selectSession = (state: RootState) => state.session;
export const useSession = () => useAppSelector(selectSession);
