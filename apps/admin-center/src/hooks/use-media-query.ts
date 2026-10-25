import * as React from "react"

/**
 * 订阅 CSS 媒体查询。服务端渲染与水合前取 `serverDefault`（默认 true，即桌面），
 * 避免服务端与客户端首帧不一致。
 */
export function useMediaQuery(query: string, serverDefault = true) {
  const subscribe = React.useCallback(
    (onChange: () => void) => {
      const mql = window.matchMedia(query)
      mql.addEventListener("change", onChange)
      return () => mql.removeEventListener("change", onChange)
    },
    [query]
  )
  return React.useSyncExternalStore(
    subscribe,
    () => window.matchMedia(query).matches,
    () => serverDefault
  )
}
