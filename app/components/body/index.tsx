import styles from "./index.module.scss";

export default function Body({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return <div className={styles.root}>{children}</div>;
}
