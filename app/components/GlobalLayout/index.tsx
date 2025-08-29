"use client";

import { store } from "@/store";
import initMocks from "@/store/mocks";
import { CssBaseline, Grid } from "@mui/material";
import { Provider } from "react-redux";
import Body from "../body";
import Header from "../header";

if (process.env.NEXT_PUBLIC_API_MOCKING === "enabled") {
  initMocks();
}

export default function ClientGlobalLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <Provider store={store}>
      <CssBaseline />
      <Grid container direction={"column"} spacing={2} padding={2}>
        <Header />
        <Body>{children}</Body>
      </Grid>
    </Provider>
  );
}
