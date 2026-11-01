import axios from 'axios'

const client = axios.create({ baseURL: '/api/v1', timeout: 30000 })

client.interceptors.request.use((config) => {
  const token = localStorage.getItem('emonlens.token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  const ws = localStorage.getItem('emonlens.workspace')
  if (ws) {
    try {
      const workspace = JSON.parse(ws)
      if (workspace?.id) config.headers['X-Workspace-Id'] = workspace.id
    } catch (e) {
      // 本地缓存损坏时清掉，避免每个请求都抛异常
      localStorage.removeItem('emonlens.workspace')
    }
  }
  return config
})

client.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body.code === 'number' && body.code !== 0) {
      if (body.code === 40101) {
        localStorage.removeItem('emonlens.token')
        if (!location.pathname.startsWith('/login')) {
          // 与下方 401 分支一致：带上回跳地址，登录后回到原页面
          location.href = '/login?redirect=' + encodeURIComponent(location.pathname + location.search)
        }
      }
      return Promise.reject(new Error(body.message || `错误码 ${body.code}`))
    }
    return body
  },
  (error) => {
    const data = error.response && error.response.data
    if (data && typeof data.code === 'number' && data.code !== 0) {
      if (data.code === 40101) {
        localStorage.removeItem('emonlens.token')
        if (!location.pathname.startsWith('/login')) {
          location.href = '/login?redirect=' + encodeURIComponent(location.pathname + location.search)
        }
      }
      // 非 2xx 但带 ApiResult 体（如 429 限流）：透出服务端 message 而非通用状态码文案
      return Promise.reject(new Error(data.message || `错误码 ${data.code}`))
    }
    if (data instanceof Blob) {
      // blob 请求（导出）出错时响应体也是 Blob：解析出 ApiResult 的真实错误信息
      return data.text().then((text) => {
        let message = null
        try {
          const parsed = JSON.parse(text)
          if (parsed && typeof parsed.code === 'number' && parsed.code !== 0) {
            message = parsed.message || `错误码 ${parsed.code}`
            if (parsed.code === 40101) {
              localStorage.removeItem('emonlens.token')
              if (!location.pathname.startsWith('/login')) {
                location.href = '/login?redirect=' + encodeURIComponent(location.pathname + location.search)
              }
            }
          }
        } catch (e) { /* 不是 JSON 错误体，按通用错误处理 */ }
        return Promise.reject(new Error(message || `导出失败（HTTP ${error.response.status}）`))
      })
    }
    if (error.response && error.response.status === 401) {
      localStorage.removeItem('emonlens.token')
      if (!location.pathname.startsWith('/login')) {
        location.href = '/login?redirect=' + encodeURIComponent(location.pathname + location.search)
      }
    }
    return Promise.reject(error)
  }
)

export default {
  async get(url, params) {
    const body = await client.get(url, { params })
    return body.data
  },
  async post(url, data, config) {
    const body = await client.post(url, data, config)
    return body.data
  },
  async put(url, data) {
    const body = await client.put(url, data)
    return body.data
  },
  async patch(url, data) {
    const body = await client.patch(url, data)
    return body.data
  },
  async delete(url, data) {
    const body = await client.delete(url, { data })
    return body.data
  },
  async getBlob(url, params) {
    // 导出接口返回文件流而不是 ApiResult；数据量可能远超全局 30s 超时，放宽到 5 分钟
    return client.get(url, { params, responseType: 'blob', timeout: 300000 })
  }
}
