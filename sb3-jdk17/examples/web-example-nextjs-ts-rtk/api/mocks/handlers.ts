import authApiHandlers from "../auth/mock";
import userApiHandlers from "../user/mock";

const handlers = [...authApiHandlers, ...userApiHandlers];
export default handlers;
