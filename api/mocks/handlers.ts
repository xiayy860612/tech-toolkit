import userApiHandlers from "../auth/getUserInfo/mock";
import authApiHandlers from "../auth/mock";

const handlers = [...authApiHandlers, ...userApiHandlers];
export default handlers;
