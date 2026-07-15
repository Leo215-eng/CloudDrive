import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'
// instance：你创建的 Axios 实例（比如 const axiosInstance = axios.create({ baseURL: 'xxx' })）
// label：可选的标签（打印日志用，方便定位哪个请求出问题）
// 它会给这个 Axios 实例自动加两套规则：
//     请求发出去前：加防缓存参数、加登录 token、补全请求头
//     响应收回来后：统一处理登录失效、统一判断请求成功 / 失败、统一提示错误
// 每次调用 authRequest({url,method...})，axios 自动合并实例配置和本次临时参数，生成完整请求对象，自动作为参数传入请求拦截器回调，这个参数就是 config
export function attachInterceptors(instance, label = '') {
  // 请求拦截器 发请求前的统一处理
  instance.interceptors.request.use(
    (config) => {
      // 1. 兜底请求头：如果config.headers不存在，先初始化一个空对象
      if (!config.headers) config.headers = {}
        // 2. 处理GET请求的浏览器缓存问题
      const method = (config.method || 'get').toLowerCase()  // 统一转小写，兼容大小写

      // 避免 GET 请求命中浏览器缓存，导致页面切换后数据不刷新
      if (method === 'get') {
          // 给GET请求的参数加一个 _t=当前时间戳
        config.params = {
          ...(config.params || {}),// 保留原来的参数
          _t: Date.now()// 加时间戳（浏览器缓存会根据URL缓存，加随机时间戳就不会命中缓存）
        }// 加请求头：明确告诉浏览器不要缓存这个请求
        config.headers['Cache-Control'] = 'no-cache'
        config.headers.Pragma = 'no-cache'
      }
        // // 3. 给请求加登录令牌（token)
      //   注册时用户还没登录，userStore.token 是空，不会往请求头塞 token，后端不会校验登录态，正常接收注册参数。
      const userStore = useUserStore() // 从状态管理中获取用户信息（比如token）
      if (userStore.token) {
          // 把token放到请求头的Authorization字段（后端鉴权常用这个字段）
        config.headers.Authorization = `${userStore.token}`
        if (label) console.log(`[${label}] set authorization token`, userStore.token)
      } else {
        if (label) console.log(`[${label}] no token in userStore`)
      }
      return config
    },
    (error) => Promise.reject(error)
  )

    // 响应拦截器：统一处理后端返回的所有接口响应、错误、登录状态、消息提示、数据格式化
    instance.interceptors.response.use(
        // 成功回调：HTTP状态码 2xx 进入这里（业务成功/业务失败都在这）
        (response) => {
            // ========== 1. 全局拦截未登录业务码 ==========
            if (
                response.data &&
                (
                    // 后端自定义业务码：未登录
                    response.data.code === 'NOT_LOGIN' ||
                    // 后端返回提示文字为未登录
                    response.data.message === '未登录'
                )
            ) {
                // 引入用户状态仓库（Pinia/Vuex）
                const userStore = useUserStore()
                // 清空本地用户信息、token、登录状态
                userStore.logout()
                // 页面强制跳转到登录页
                window.location.href = '/login'
                // 返回失败Promise，上层接口捕获可做额外处理
                return Promise.reject(new Error('未登录'))
            }

            // 打印日志，方便调试查看原始响应
            console.log(`[${label}] 响应拦截器收到响应:`, response)
            console.log(`[${label}] 响应数据:`, response.data)

            // ========== 2. 特殊兼容：登录接口返回纯字符串 "SUCCESS"（非标准JSON统一响应体） ==========
            if (typeof response.data === 'string') {
                console.log(`[${label}] 响应是字符串:`, response.data)
                console.log(`[${label}] 响应头:`, response.headers)

                // 登录接口返回字符串SUCCESS代表登录成功
                if (response.data === 'SUCCESS') {
                    console.log(`[${label}] 登录成功`)
                    // 从多个响应头兼容读取后端下发的token（多套header适配）
                    const token = response.headers['authorization'] || response.headers['Authorization'] || response.headers['x-access-token']
                    console.log(`[${label}] 从响应头获取的token:`, token)
                    // 手动包装成全局统一返回格式，方便页面统一取值
                    return {
                        code: 200,
                        data: { token: token, message: response.data },
                        message: '登录成功'
                    }
                } else {
                    // 字符串非SUCCESS，代表登录失败，弹窗提示
                    console.log(`[${label}] 登录失败:`, response.data)
                    ElMessage.error(response.data || '登录失败')
                    // 抛出异常，让页面await捕获失败
                    return Promise.reject(new Error(response.data || '登录失败'))
                }
            }

            // ========== 3. 标准接口：后端返回统一JSON响应体（带code/success字段） ==========
            if (response.data && response.data.code !== undefined) {
                console.log(`[${label}] 响应code:`, response.data.code)
                // 两种成功标识兼容：数字code=200 / 布尔success=true
                if (response.data.code === 200 || response.data.success === true) {
                    console.log(`[${label}] 响应成功，返回数据:`, response.data)
                    // 直接返回标准响应体，页面可直接取data拿业务数据
                    return response.data
                } else {
                    // 特殊业务：秒传文件提示文件不存在，属于正常业务分支，不弹报错
                    const isSecUploadNotExists =
                        response.config?.url?.includes('/file/sec-upload') &&
                        response.data.code === 'FILE_NOT_EXIT'
                    if (isSecUploadNotExists) {
                        // 放行给上层业务处理，走分片上传逻辑
                        return response.data
                    }
                    // 其余业务错误统一弹窗提示
                    console.log(`[${label}] 响应失败:`, response.data.message)
                    ElMessage.error(response.data.message || '请求失败')
                    return Promise.reject(new Error(response.data.message || '请求失败'))
                }
            }

            // ========== 4. 兜底：返回格式不匹配任何规则，直接透传原始响应 ==========
            console.log(`[${label}] 响应数据格式不符合预期，直接返回:`, response)
            return response
        },

        // 失败回调：HTTP状态码 4xx/5xx、网络超时、跨域等HTTP层面错误进入这里
        (error) => {
            console.log(`[${label}] 响应拦截器错误:`, error)
            console.log(`[${label}] 错误响应:`, error.response)

            // HTTP 401：未授权/token过期
            if (error.response?.status === 401) {
                const userStore = useUserStore()
                // 清空登录信息
                userStore.logout()
                // 强制跳转登录页刷新页面，清除前端缓存状态
                window.location.href = '/login'
            }
            // 统一弹出网络/请求错误提示
            ElMessage.error(error.message || '网络错误')
            // 抛出错误，上层接口可catch捕获
            return Promise.reject(error)
        }
    )

}