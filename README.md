# 前端脚手架

## 功能

- 用户名/密码登陆

## Tech Stack

- [TypeScript](https://www.typescriptlang.org/)
- [nextjs](https://nextjs.org/)，前端全栈框架
- [React](https://zh-hans.react.dev/)，前端框架
- [RTK](https://redux-toolkit.js.org/)，状态管理
- [Tailwind CSS](https://tailwindcss.com/)，样式
- [Jest](https://nextjs.org/docs/app/building-your-application/testing/jest)，单元测试
- [Axios](https://axios-http.com/)，网络库
- [MSW](), API mock

## Local Test

### How to define a new model

1. `npx knex --knexfile knexfile.ts migrate:make <migration-file-name>` create migration file.
2. `npx knex --knexfile knexfile.ts migrate:latest` apply migrations to db.
3. `npx knex migrate:rollback` could rollback latest migration

prepare some test data:

1. `npx knex --knexfile knexfile.ts seed:make <seed-name>` create test data seed file
2. `npx knex --knexfile knexfile.ts seed:run` apply test data to db.

## Environments

- [kids-resource test env](https://kids-resource.onrender.com)

## Reference

- [CSS Flexbox Layout Guide](https://css-tricks.com/snippets/css/a-guide-to-flexbox/)