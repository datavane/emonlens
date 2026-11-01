/** 统一剪贴板复制：不可用/被拒时返回 false，调用方不要假装成功。 */
export async function copyToClipboard(text) {
  try {
    if (!navigator.clipboard?.writeText) return false
    await navigator.clipboard.writeText(text == null ? '' : String(text))
    return true
  } catch (e) {
    return false
  }
}
