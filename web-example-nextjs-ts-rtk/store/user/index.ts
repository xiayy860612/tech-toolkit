import { UserInfo } from "@/api/user";
import { createSlice, PayloadAction } from "@reduxjs/toolkit";
import { RootState } from "..";

interface UserState {
  user: UserInfo | null;
}

const initialState: UserState = {
  user: null,
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
export const selectUser = (state: RootState) => state.user;
