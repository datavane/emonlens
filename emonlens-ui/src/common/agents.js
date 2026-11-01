import api from '@/api'

/**
 * Agent 下拉选项：以「近 30 天有上报」的 Agent 为主（/agents/health），
 * 再并入已生成信任分快照的 Agent（/score/overview）。
 *
 * 不能只用 score/overview：信任分需要足够证据才会产生快照，
 * 新接入或证据不足的 Agent 在筛选器里会“消失”，用户以为数据丢了。
 */
export async function fetchAgentOptions() {
  const [health, overview] = await Promise.all([
    api.agentHealth().catch(() => []),
    api.scoreOverview().catch(() => ({}))
  ])
  const keys = new Set()
  for (const row of health || []) {
    if (row.agentKey) keys.add(row.agentKey)
  }
  for (const key of Object.keys(overview || {})) {
    keys.add(key)
  }
  return [...keys].sort().map((k) => ({ label: k, value: k }))
}
