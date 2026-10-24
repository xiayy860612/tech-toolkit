import { mapUamPasswordFieldErrors, parseChangePasswordForm } from "@/lib/validation/password";

const CURRENT = "Old-Pass-12345";
const NEW = "New-Pass-67890";

function form(values: { currentPassword?: string | null; newPassword?: string | null; confirmPassword?: string | null } = {}) {
  const fd = new FormData();
  const v = { currentPassword: CURRENT, newPassword: NEW, confirmPassword: NEW, ...values };
  for (const [k, val] of Object.entries(v)) if (val !== null) fd.set(k, val);
  return fd;
}

describe("parseChangePasswordForm", () => {
  it("合法输入：只返回当前密码与新密码（确认项不外传）", () => {
    expect(parseChangePasswordForm(form())).toEqual({ success: true, data: { currentPassword: CURRENT, newPassword: NEW } });
  });

  it("三项都空：三处必填提示", () => {
    const parsed = parseChangePasswordForm(form({ currentPassword: "", newPassword: "", confirmPassword: "" }));
    expect(parsed).toEqual({
      success: false,
      fieldErrors: { currentPassword: "请填写当前密码", newPassword: "请填写新密码", confirmPassword: "请再次输入新密码" },
    });
  });

  it("缺字段（非字符串）按空处理", () => {
    const parsed = parseChangePasswordForm(new FormData());
    expect(parsed.success).toBe(false);
    expect(Object.keys(!parsed.success ? parsed.fieldErrors : {}).sort()).toEqual(["confirmPassword", "currentPassword", "newPassword"]);
  });

  it.each([["short1A"], ["onlyletters-long"], ["1234567890123"]])("新密码 %j 太弱：给出强度要求", (weak) => {
    const parsed = parseChangePasswordForm(form({ newPassword: weak, confirmPassword: weak }));
    expect(parsed).toEqual({ success: false, fieldErrors: { newPassword: "新密码至少 10 位，且同时包含字母和数字" } });
  });

  it("新密码与当前密码相同：给出原因（不论强度）", () => {
    const parsed = parseChangePasswordForm(form({ currentPassword: NEW }));
    expect(parsed).toEqual({ success: false, fieldErrors: { newPassword: "新密码不能与当前密码相同" } });
  });

  it("两次输入的新密码不一致：原因在确认项旁", () => {
    const parsed = parseChangePasswordForm(form({ confirmPassword: `${NEW}x` }));
    expect(parsed).toEqual({ success: false, fieldErrors: { confirmPassword: "两次输入的新密码不一致" } });
  });

  it("确认项为空与不一致是两种提示", () => {
    expect(parseChangePasswordForm(form({ confirmPassword: "" }))).toMatchObject({
      fieldErrors: { confirmPassword: "请再次输入新密码" },
    });
  });

  it("新密码太弱且确认不一致：两处各给原因", () => {
    const parsed = parseChangePasswordForm(form({ newPassword: "weak", confirmPassword: "other" }));
    expect(parsed).toEqual({
      success: false,
      fieldErrors: { newPassword: "新密码至少 10 位，且同时包含字母和数字", confirmPassword: "两次输入的新密码不一致" },
    });
  });

  it("超长（> 256）在本地拒绝；恰好 256 通过", () => {
    const tooLong = `${"a1".repeat(128)}x`;
    expect(parseChangePasswordForm(form({ newPassword: tooLong, confirmPassword: tooLong }))).toMatchObject({
      success: false,
      fieldErrors: { newPassword: "密码最长 256 个字符" },
    });
    expect(parseChangePasswordForm(form({ currentPassword: tooLong }))).toMatchObject({
      success: false,
      fieldErrors: { currentPassword: "密码最长 256 个字符" },
    });
    const exact = "a1".repeat(128);
    expect(parseChangePasswordForm(form({ newPassword: exact, confirmPassword: exact })).success).toBe(true);
  });

  it("密码不去首尾空白（空白也是密码的一部分）", () => {
    const padded = `  ${NEW}  `;
    expect(parseChangePasswordForm(form({ newPassword: padded, confirmPassword: padded }))).toEqual({
      success: true,
      data: { currentPassword: CURRENT, newPassword: padded },
    });
  });
});

describe("mapUamPasswordFieldErrors", () => {
  it("按字段与原因给文案；未知字段忽略，未知原因用通用文案；同一字段取第一条", () => {
    expect(
      mapUamPasswordFieldErrors([
        { field: "newPassword", reason: "too_weak" },
        { field: "newPassword", reason: "required" },
        { field: "currentPassword", reason: "required" },
        { field: "clientIp", reason: "required" },
      ]),
    ).toEqual({ newPassword: "新密码至少 10 位，且同时包含字母和数字", currentPassword: "请填写当前密码" });
    expect(mapUamPasswordFieldErrors([{ field: "newPassword", reason: "too_long" }])).toEqual({ newPassword: "密码最长 256 个字符" });
    expect(mapUamPasswordFieldErrors([{ field: "currentPassword", reason: "brand_new" }])).toEqual({ currentPassword: "输入不合法" });
    expect(mapUamPasswordFieldErrors([])).toEqual({});
  });
});
