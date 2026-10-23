import "@testing-library/jest-dom";

// jsdom 缺少的浏览器 API（Radix UI / shadcn 组件需要）。node 环境的测试没有 window，跳过。
if (typeof window !== "undefined") {
  // matchMedia：按当前 window.innerWidth 计算 (min-width) / (max-width) 查询，测试里改 innerWidth 即可切换视口
  window.matchMedia = (query: string): MediaQueryList => {
    const evaluate = () => {
      const min = /\(min-width:\s*(\d+)px\)/.exec(query);
      const max = /\(max-width:\s*(\d+)px\)/.exec(query);
      return (min ? window.innerWidth >= Number(min[1]) : true) && (max ? window.innerWidth <= Number(max[1]) : true);
    };
    return {
      get matches() {
        return evaluate();
      },
      media: query,
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => false,
    };
  };
  window.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  Element.prototype.hasPointerCapture ??= () => false;
  Element.prototype.setPointerCapture ??= () => undefined;
  Element.prototype.releasePointerCapture ??= () => undefined;
  Element.prototype.scrollIntoView ??= () => undefined;
}
