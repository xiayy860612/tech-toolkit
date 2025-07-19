"use client";

import { useLoginMutation } from "@/api/auth";
import { useAppDispatch } from "@/store";
import { setUserAction } from "@/store/user";
import { Button, Form, Input } from "antd";
import { useRouter } from "next/navigation";
import styles from "./styles.module.css";

interface LoginFormProps {
  username: string;
  password: string;
}

export default function Page() {
  const [login] = useLoginMutation();
  const dispatch = useAppDispatch();
  const router = useRouter();

  const handleFinish = async (form: LoginFormProps) => {
    console.log(form);
    const result = await login({ ...form });
    dispatch(setUserAction(result.data!.user));
    router.push("/");
  };

  const handleFinishFailed = (errorInfo: unknown) => {
    console.log(errorInfo);
  };

  return (
    <div className={styles.container}>
      <Form
        name="basic"
        labelCol={{ span: 8 }}
        wrapperCol={{ span: 16 }}
        style={{ maxWidth: 600 }}
        initialValues={{ remember: true }}
        onFinish={handleFinish}
        onFinishFailed={handleFinishFailed}
        autoComplete="off"
      >
        <Form.Item<LoginFormProps>
          label="Username"
          name="username"
          rules={[{ required: true, message: "Please input your username!" }]}
        >
          <Input />
        </Form.Item>

        <Form.Item<LoginFormProps>
          label="Password"
          name="password"
          rules={[{ required: true, message: "Please input your password!" }]}
        >
          <Input.Password />
        </Form.Item>

        <Form.Item label={null}>
          <Button type="primary" htmlType="submit">
            Submit
          </Button>
        </Form.Item>
      </Form>
    </div>
  );
}
