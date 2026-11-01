/** 时长/成本/Token 的统一格式化（各视图共用，勿再复制本地实现）。 */

export function fmtDur(ms) {
  if (ms == null) return '—'
  if (ms >= 60000) return (ms / 60000).toFixed(1) + ' min'
  if (ms >= 1000) return (ms / 1000).toFixed(2) + ' s'
  return ms + ' ms'
}

/** 成本：null/0 视为 $0（会话、用户等聚合口径） */
export function fmtCost(v) {
  const n = Number(v || 0)
  if (n === 0) return '$0'
  return '$' + n.toFixed(4).replace(/0+$/, '').replace(/\.$/, '')
}

/** 成本：null 显示 —（单条 trace 未计价口径） */
export function fmtCostNullable(v) {
  if (v == null) return '—'
  return fmtCost(v)
}

export function fmtTokens(v) {
  const n = Number(v || 0)
  if (!n) return '0'
  if (n >= 1e6) return (n / 1e6).toFixed(2) + 'M'
  if (n >= 1e3) return (n / 1e3).toFixed(1) + 'K'
  return String(n)
}
