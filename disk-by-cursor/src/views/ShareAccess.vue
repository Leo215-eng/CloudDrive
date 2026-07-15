<template>
  <div class="share-access-page">
    <!-- 卡片容器，包含加载状态（加载分享信息时显示） -->
    <el-card class="share-access-card" v-loading="loadingShareInfo">
      <template #header>
        <div class="card-header">分享访问</div>
      </template>
      <!-- 错误提示区域：当存在errorMessage时显示（如shareId缺失、分享失效） -->
      <el-result
        v-if="errorMessage"
        icon="error"
        title="链接不可用"
        :sub-title="errorMessage"
      />

      <!-- 分享信息加载成功后的主内容区域 -->
      <template v-else-if="shareInfo">
        <!-- 分享基础信息展示 -->
        <div class="share-info">
          <h2 class="share-title">{{ shareInfo.shareName }}</h2>
          <p>状态：{{ shareInfo.shareStatus === 0 ? '正常' : '异常' }}</p>
          <p>过期时间：{{ formatDateTime(shareInfo.shareEndTime, '永久有效') }}</p>
        </div>
        <!-- 私有分享且未验证提取码时：显示提取码输入面板 -->
        <div v-if="isPrivateShare && !shareVerified" class="share-code-panel">
          <el-alert type="warning" show-icon :closable="false" title="该分享需要提取码" />
          <el-input
            v-model="shareCodeInput"
            maxlength="12"
            placeholder="请输入提取码"
            class="share-code-input"
            @keyup.enter="handleCheckCode"
          />
          <el-button type="primary" :loading="checkingCode" @click="handleCheckCode">验证提取码</el-button>
        </div>
        <!-- 公有分享 或 私有分享已验证：显示文件列表面板 -->
        <div v-else class="share-files-panel">
          <div class="panel-title">分享文件</div>
          <el-table :data="shareFiles" v-loading="loadingFiles" style="width: 100%">
            <el-table-column prop="filename" label="文件名" min-width="260" />
            <el-table-column prop="fileSizeDesc" label="大小" width="120" />
            <el-table-column label="更新时间" width="180">
              <template #default="{ row }">
                {{ formatDateTime(row.updateTime, '--') }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button
                  type="primary"
                  link
                  :disabled="Number(row.folderFlag) === 1"
                  @click="handleDownload(row)"
                >
                  下载
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <!-- 空状态：文件加载完成且列表为空时显示 -->
          <el-empty v-if="!loadingFiles && shareFiles.length === 0" description="该分享暂无可下载文件" />
        </div>
      </template>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { checkShareCode, downloadShareFile, getShareFiles, getShareSimpleDetail } from '@/api/share'

// 初始化路由实例（用于获取URL参数）
const route = useRoute()

// ========== 响应式数据定义 ==========
// 加载分享基础信息的loading状态
const loadingShareInfo = ref(false)
// 加载分享文件列表的loading状态
const loadingFiles = ref(false)
// 验证提取码的loading状态
const checkingCode = ref(false)
// 错误提示信息（如分享失效、参数缺失）
const errorMessage = ref('')
// 分享基础信息（接口返回的分享名称、状态、类型等）
const shareInfo = ref(null)
// 分享的文件列表数据
const shareFiles = ref([])
// 提取码输入框绑定值
const shareCodeInput = ref('')
// 提取码验证状态（私有分享验证成功后为true）
const shareVerified = ref(false)

// ========== 计算属性 ==========
// 从路由参数中获取shareId（分享唯一标识）
// 这里的 shareId 不是数据库真实 ID，而是后端加密后的字符串。这样别人看链接时看不到真实数据库主键。
const shareId = computed(() => route.query.shareId || '')

// 判断是否为私有分享：shareType=1 代表私有（需要提取码）
const isPrivateShare = computed(() => Number(shareInfo.value?.shareType) === 1)

// ========== 工具函数 ==========
/**
 * 格式化时间戳/日期字符串
 * @param {string|number} value - 待格式化的时间值
 * @param {string} emptyText - 无值时的占位文本（默认'--'）
 * @returns {string} 格式化后的中文本地化时间，或占位文本
 */
function formatDateTime(value, emptyText = '--') {
  if (!value) return emptyText
  const date = new Date(value)
  // 校验时间格式是否合法
  if (Number.isNaN(date.getTime())) return value
  // 格式化为中文本地化时间（如：2025/5/20 15:30:20）
  return date.toLocaleString('zh-CN')
}

/**
 * 从响应头的Content-Disposition中解析下载文件名
 * @param {string} disposition - Content-Disposition响应头值
 * @param {string} fallbackName - 解析失败时的默认文件名
 * @returns {string} 解析后的文件名
 */
function parseFilenameFromDisposition(disposition, fallbackName = 'download') {
  if (!disposition) return fallbackName
  // 优先解析UTF-8编码的文件名（兼容中文）
  const utf8Match = disposition.match(/filename\*=UTF-8''([^;]+)/i)
  if (utf8Match && utf8Match[1]) {
    try {
      // 解码URI编码的文件名，并移除引号
      return decodeURIComponent(utf8Match[1]).replace(/["']/g, '')
    } catch (_) {
      return utf8Match[1].replace(/["']/g, '')
    }
  }
  // 兼容普通filename格式
  const filenameMatch = disposition.match(/filename="?([^\";]+)"?/i)
  return filenameMatch?.[1] || fallbackName
}

/**
 * 将Blob数据转换为下载链接并触发下载
 * @param {Blob} blob - 下载的文件Blob数据
 * @param {string} filename - 文件名
 */
function saveBlob(blob, filename) {
  // 创建Blob临时URL
  const url = window.URL.createObjectURL(blob)
  // 创建<a>标签触发下载
  const link = document.createElement('a')
  link.href = url
  link.download = filename // 设置下载文件名
  document.body.appendChild(link)
  link.click() // 触发点击下载
  // 清理DOM和临时URL
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}

// ========== 核心业务函数 ==========
/**
 * 加载分享基础信息
 * 1. 校验shareId是否存在
 * 2. 调用接口获取分享信息
 * 3. 公有分享直接加载文件列表，私有分享等待提取码验证
 */
async function loadShareInfo() {
  // 缺少shareId时提示错误
  if (!shareId.value) {
    errorMessage.value = '缺少 shareId 参数'
    return
  }
  loadingShareInfo.value = true
  try {
    // 调用接口获取分享基础信息
    const response = await getShareSimpleDetail({ shareId: shareId.value })
    // 接口返回失败/无数据：提示分享失效
    if (!response?.success || !response?.data) {
      errorMessage.value = response?.message || '分享不存在或已失效'
      return
    }
    // 保存分享基础信息
    shareInfo.value = response.data
    // 公有分享：直接标记为已验证，并加载文件列表
    if (!isPrivateShare.value) {
      shareVerified.value = true
      await loadShareFiles()
    }
  } catch (error) {
    // 接口异常：提示加载失败
    errorMessage.value = error?.message || '加载分享信息失败'
  } finally {
    // 无论成功/失败，关闭loading
    loadingShareInfo.value = false
  }
}


/**
* 加载分享的文件列表
* 私有分享需携带提取码参数
*/
async function loadShareFiles() {
  if (!shareId.value) return
  loadingFiles.value = true
  try {
    // 构造接口参数（必传shareId）
    const params = { shareId: shareId.value }
    // 私有分享：追加提取码参数
    if (isPrivateShare.value) {
      params.shareCode = shareCodeInput.value.trim()
    }
    // 调用接口获取文件列表
    const response = await getShareFiles(params)
    if (response?.success) {
      // 保存文件列表（确保是数组格式）
      shareFiles.value = Array.isArray(response.data) ? response.data : []
      return
    }
    // 接口返回失败：清空列表并提示错误
    shareFiles.value = []
    ElMessage.error(response?.message || '加载分享文件失败')
  } catch (error) {
    // 接口异常：清空列表并提示错误
    shareFiles.value = []
    ElMessage.error(error?.message || '加载分享文件失败')
  } finally {
    loadingFiles.value = false
  }
}


/**
 * 处理提取码验证逻辑
 * 1. 校验提取码是否为空
 * 2. 调用验证接口
 * 3. 验证成功后加载文件列表
 */
async function handleCheckCode() {
  const shareCode = shareCodeInput.value.trim()
  // 提取码为空时提示
  if (!shareCode) {
    ElMessage.warning('请输入提取码')
    return
  }
  checkingCode.value = true
  try {
    // 调用提取码验证接口
    const response = await checkShareCode({
      shareId: shareId.value,
      shareCode
    })
    // 验证失败：提示错误
    if (!response?.success) {
      ElMessage.error(response?.message || '提取码验证失败')
      return
    }
    // 验证成功：标记状态并加载文件列表
    shareVerified.value = true
    ElMessage.success('提取码验证成功')
    await loadShareFiles()
  } catch (error) {
    // 接口异常：提示验证失败
    ElMessage.error(error?.message || '提取码验证失败')
  } finally {
    checkingCode.value = false
  }
}


/**
 * 处理文件下载逻辑
 * 1. 禁用文件夹下载
 * 2. 调用下载接口获取Blob数据
 * 3. 解析文件名并触发下载
 */
async function handleDownload(file) {
  // folderFlag=1 代表文件夹，提示暂不支持下载
  if (Number(file?.folderFlag) === 1) {
    ElMessage.warning('暂不支持从分享页直接下载文件夹')
    return
  }
  try {
    // 构造下载接口参数
    const params = {
      shareId: shareId.value,
      fileId: file.fileId // 文件唯一标识
    }
    // 私有分享：追加提取码参数
    if (isPrivateShare.value) {
      params.shareCode = shareCodeInput.value.trim()
    }
    // 调用下载接口（需确保接口返回Blob格式）
    const response = await downloadShareFile(params)
    const blob = response?.data
    if (!blob) {
      throw new Error('下载失败')
    }
    // 从响应头解析文件名
    const disposition = response?.headers?.['content-disposition'] || response?.headers?.['Content-Disposition']
    const filename = parseFilenameFromDisposition(disposition, file.filename || 'download')
    // 触发文件下载
    saveBlob(blob, filename)
    ElMessage.success('下载开始')
  } catch (error) {
    // 下载失败：提示错误
    ElMessage.error(error?.message || '下载失败')
  }
}

// ========== 生命周期 ==========
// 组件挂载后，立即加载分享信息
onMounted(() => {
  loadShareInfo()
})
</script>

<style scoped>
.share-access-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: #f5f7fa;
}

.share-access-card {
  width: 100%;
  max-width: 900px;
}

.card-header {
  font-size: 18px;
  font-weight: 600;
}

.share-info {
  margin-bottom: 16px;
}

.share-title {
  margin: 0 0 12px;
}

.share-code-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.share-code-input {
  max-width: 320px;
}

.share-files-panel {
  margin-top: 12px;
}

.panel-title {
  margin-bottom: 12px;
  font-weight: 600;
}
</style>
