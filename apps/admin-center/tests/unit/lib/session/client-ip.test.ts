/**
 * @jest-environment node
 */
import { getClientIp } from "@/lib/session/client-ip";

const xff = (value: string) => new Headers({ "x-forwarded-for": value });

describe("getClientIp：按可信跳数取终端来源地址", () => {
  it("1 跳：取 X-Forwarded-For 最右一项（可信代理追加的），不信用户自带的前缀", () => {
    expect(getClientIp(xff("6.6.6.6, 203.0.113.9"), 1)).toBe("203.0.113.9");
  });

  it("1 跳：用户没带前缀时同样正确", () => {
    expect(getClientIp(xff("203.0.113.9"), 1)).toBe("203.0.113.9");
  });

  it("2 跳：取右数第二项", () => {
    expect(getClientIp(xff("6.6.6.6, 203.0.113.9, 10.1.1.1"), 2)).toBe("203.0.113.9");
  });

  it("容忍空白与 IPv6", () => {
    expect(getClientIp(xff("  2001:db8::1 ,10.0.0.1"), 2)).toBe("2001:db8::1");
  });

  it("项数少于信任跳数：链路与配置不符，不采信 → null", () => {
    expect(getClientIp(xff("203.0.113.9"), 2)).toBeNull();
  });

  it("0 跳（不信任任何代理头）→ null，无论用户带了什么", () => {
    expect(getClientIp(xff("203.0.113.9"), 0)).toBeNull();
  });

  it("没有 X-Forwarded-For → null", () => {
    expect(getClientIp(new Headers(), 1)).toBeNull();
  });

  it("取到的值不是合法 IP → null（不把任意字符串交给 UAM）", () => {
    expect(getClientIp(xff("1.1.1.1, <script>"), 1)).toBeNull();
    expect(getClientIp(xff(""), 1)).toBeNull();
  });
});
