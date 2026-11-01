// CSV 导出工具（对标 Langfuse Export CSV）
export function toCsv(headers, rows) {
  const esc = (v) => {
    let s = v == null ? '' : String(v)
    // 防公式注入：Excel/Sheets 会把以 = + - @ 或制表符开头的单元格当公式执行
    if (/^[=+\-@\t\r]/.test(s)) s = "'" + s
    return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
  }
  return [headers.map(esc).join(','), ...rows.map((r) => r.map(esc).join(','))].join('\n')
}

export function downloadCsv(filename, csv) {
  // BOM 保证 Excel 打开中文不乱码
  const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}
