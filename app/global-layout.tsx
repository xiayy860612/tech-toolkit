"use client";

import initMocks from "@/api/mocks";
import { store } from "@/store";
import config from "@/theme/themeConfig";
import { AntdRegistry } from "@ant-design/nextjs-registry";
import { ConfigProvider, Layout } from "antd";
import { Provider } from "react-redux";
import { NavBar } from "./components/nav-bar";

const { Header, Content, Footer } = Layout;

if (process.env.NEXT_PUBLIC_API_MOCKING === "enabled") {
  initMocks();
}

export function ClientGlobalLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <Provider store={store}>
      <ConfigProvider theme={config}>
        <AntdRegistry>
          <Layout>
            <Header className="w-full">
              <NavBar />
            </Header>
            <Content className="h-screen">{children}</Content>
            <Footer style={{ textAlign: "center" }}>Footer</Footer>
          </Layout>
        </AntdRegistry>
      </ConfigProvider>
    </Provider>
  );
}
