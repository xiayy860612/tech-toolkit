import { MenuInfo, UserInfo } from "@/types/model/user";
import { createSlice, PayloadAction } from "@reduxjs/toolkit";
import { RootState, useAppSelector } from "..";

interface UserState {
  user: UserInfo | null;
  menus: MenuInfo[];
}

const initialState: UserState = {
  user: null,
  menus: [
    {
      name: "Demo",
      link: "/demo-page",
    },
  ],
};

const slice = createSlice({
  name: "user",
  initialState,
  reducers: {
    setUserAction: (state, action: PayloadAction<UserInfo>) => {
      state.user = action.payload;
    },
  },
});

export const { setUserAction } = slice.actions;
export const userReducer = slice.reducer;

const selectUser = (state: RootState) => state.user;
export const useUser = () => useAppSelector(selectUser);
