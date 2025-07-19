"use client";

import { useAppSelector } from "@/store";
import { selectUser } from "@/store/user";
import { Menu } from "antd";
import { ItemType } from "antd/es/menu/interface";
import Link from "next/link";
import { UserCard } from "../user-card";
import styles from "./styles.module.css";

interface MenuItem {
  name: string;
  link?: string;
  subItems?: MenuItem[];
}

export function NavBar() {
  const { user } = useAppSelector(selectUser);

  const menuItems: MenuItem[] = [
    { name: "m1", link: "/m1" },
    {
      name: "m2",
      subItems: [
        { name: "ms1", link: "/ms1" },
        { name: "ms2", link: "/ms2" },
      ],
    },
    { name: "m3", link: "/m3" },
    {
      name: "m4",
      subItems: [
        { name: "ms41", link: "/ms41" },
        { name: "ms42", link: "/ms42" },
      ],
    },
  ];

  const menuItemRender = (item: MenuItem): ItemType => {
    if (item.link) {
      return {
        key: item.name,
        label: (
          <Link href={item.link} passHref>
            {item.name}
          </Link>
        ),
      };
    }

    return {
      key: item.name,
      label: item.name,
      children: item.subItems?.map((subItem) => menuItemRender(subItem)),
    };
  };

  return (
    <div className={styles.header}>
      <div className="logo">
        <Link href="/" passHref>
          <h5 className={styles.title}>Logo</h5>
        </Link>
      </div>
      <Menu
        className={styles.menu}
        theme="dark"
        mode="horizontal"
        items={menuItems.map((item) => menuItemRender(item))}
      />
      <div className={styles.userInfo}>
        <UserCard userInfo={user} />
      </div>
    </div>
  );
}
