<template>
  <div class="shares-container">
    <!-- 页面标题和工具栏 -->
    <div class="page-header">
      <h2>我的分享</h2>
      <div class="header-actions">
        <!-- 点击弹出创建分享弹窗 -->
      <el-button type="primary" @click="showCreateShareDialog = true">
        <el-icon><Share /></el-icon>
        创建分享
      </el-button>
      </div>
    </div>

    <!-- ========== 分享列表卡片容器 ========== -->
    <el-card>
      <!-- 分享数据表格，shareList为后端返回分享数组，loading控制加载动画 -->
      <el-table 
        :data="shareList" 
        style="width: 100%" 
        v-loading="loading"
      >
        <!-- 第一列：分享名称 + 悬浮操作按钮（查看/续期/删除） -->
        <el-table-column label="分享名称" min-width="200">
          <!-- 插槽 row = 当前行整条分享数据对象 -->
          <template #default="{ row }">
            <!-- 单行外层容器，监听鼠标移入移出 -->
            <!-- 鼠标移入显示操作按钮 -->
            <!-- 鼠标移出隐藏操作按钮 -->
            <div class="share-row-container" 
                 @mouseenter="showOperation(row, $event)" 
                 @mouseleave="hiddenOperation(row, $event)">
              <!-- 左侧图标+分享名称 -->
              <div class="share-name-content">
                <el-icon class="share-icon" color="#409eff">
                  <Share />
                </el-icon>
                <span class="share-name">{{ row.shareName }}</span>
              </div>
              <!-- 右侧悬浮操作按钮组，默认隐藏，hover才展示 -->
              <div class="share-operation-content">
                <div class="pan-share-operations">
                  <!-- 查看详情按钮 -->
                  <el-tooltip class="item" effect="light" content="查看详情" placement="top">
                    <el-button icon="View" type="info" size="small" circle @click="viewShareDetail(row)"></el-button>
                  </el-tooltip>
                  <!-- 分享续期按钮 -->
                  <el-tooltip class="item" effect="light" content="续期" placement="top">
                    <el-button icon="Refresh" type="warning" size="small" circle @click="extendShare(row)"></el-button>
                  </el-tooltip>
                  <!-- 取消/删除分享按钮 -->
                  <el-tooltip class="item" effect="light" content="取消分享" placement="top">
                    <el-button icon="Delete" type="danger" size="small" circle @click="cancelShare(row)"></el-button>
                  </el-tooltip>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>
        <!-- 第二列：分享链接 + 复制按钮 -->
        <el-table-column prop="shareUrl" label="分享链接" min-width="300" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="share-url-cell">
              <!-- 链接图标+完整地址文本 -->
              <div class="share-url-content">
                <el-icon class="link-icon"><Link /></el-icon>
                <span class="share-url-text">{{ row.shareUrl }}</span>
              </div>
              <!-- 一键复制链接按钮 -->
              <el-button 
                @click="copyShareUrl(row)" 
                size="small"
                type="primary"
                class="copy-btn"
                :icon="CopyDocument"
              >
                复制
              </el-button>
            </div>
                </template>
        </el-table-column>
        <!-- 第三列：分享提取码，点击直接复制 -->
        <el-table-column prop="shareCode" label="分享码" width="120" align="center">
          <template #default="{ row }">
            <div class="share-code-display" @click="copyShareCode(row)" title="点击复制分享码">
              <el-icon class="code-icon"><Key /></el-icon>
              <span class="share-code-text">{{ row.shareCode }}</span>
            </div>
          </template>
        </el-table-column>
        <!-- 第四列：过期时间，通过方法格式化日期展示 -->
        <el-table-column prop="shareEndTime" label="过期时间" width="150" align="center">
          <template #default="{ row }">
            <span class="expire-time">{{ formatExpireTime(row.shareEndTime) }}</span>
          </template>
        </el-table-column>
        <!-- 第五列：分享状态标签（正常/已过期/已取消） -->
        <el-table-column prop="shareStatus" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.shareStatus)" size="small">
              {{ getStatusText(row.shareStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        

      </el-table>
    </el-card>


    <!-- ========== 空数据占位：没有任何分享时显示 ========== -->
    <el-empty v-if="shareList.length === 0 && !loading" description="暂无分享" />

    <!-- ========== 创建分享弹窗 600px宽度 ========== -->
    <el-dialog 
      v-model="showCreateShareDialog" 
      title="创建分享" 
      width="600px"
      :close-on-click-modal="false"
      class="pan-dialog"
    >
      <!-- 分享表单，绑定校验规则 -->
      <el-form :model="shareForm" :rules="shareRules" ref="shareFormRef" label-width="100px">
        <el-form-item label="分享名称" prop="shareName">
          <el-input v-model="shareForm.shareName" placeholder="请输入分享名称" />
        </el-form-item>
        <!-- 穿梭框：选择要分享的文件，左边可选，右边已选中 -->
        <el-form-item label="分享文件" prop="shareFileIds">
          <div v-loading="loadingShareFiles">
<!--            v-model="shareForm.shareFileIds"  &lt;!&ndash; 绑定选中文件ID数组 &ndash;&gt;-->
<!--            :data="fileList"                 &lt;!&ndash; 全部可选择文件数据源 &ndash;&gt;-->
<!--            :titles="['可选文件', '已选文件']"-->
<!--            :props="{-->
<!--            key: 'fileId',    &lt;!&ndash; 每条文件唯一标识字段 &ndash;&gt;-->
<!--            label: 'filename' &lt;!&ndash; 页面展示文件名 &ndash;&gt;-->
<!--            }"-->
          <el-transfer
            v-model="shareForm.shareFileIds"
            :data="fileList"
            :titles="['可选文件', '已选文件']"
            :props="{
              key: 'fileId',
              label: 'filename'
            }"
            style="width: 100%"
          />
          </div>
        </el-form-item>
        <!-- 分享类型单选：0公开 /1私密带提取码 -->
        <el-form-item label="分享类型" prop="shareType">
          <el-radio-group v-model="shareForm.shareType">
            <el-radio :label="0">公开分享</el-radio>
            <el-radio :label="1">私密分享</el-radio>
          </el-radio-group>
        </el-form-item>
        <!-- 过期时效下拉选择框 -->
        <el-form-item label="过期时间" prop="shareDayType">
          <el-select v-model="shareForm.shareDayType" placeholder="请选择过期时间">
            <el-option label="1天" :value="1" />
            <el-option label="7天" :value="7" />
            <el-option label="30天" :value="30" />
            <el-option label="永久" :value="-1" />
          </el-select>
        </el-form-item>
      </el-form>
      <!-- 弹窗底部按钮插槽 -->
      <template #footer>
        <span class="dialog-footer">
        <el-button @click="showCreateShareDialog = false">取消</el-button>
        <el-button type="primary" @click="createShare">确定</el-button>
        </span>
      </template>
    </el-dialog>


    <!-- ========== 分享详情弹窗 800px宽度 ========== -->
    <el-dialog 
      v-model="showDetailDialog" 
      title="分享详情" 
      width="800px"
      class="pan-dialog"
    >
      <!-- currentShare 存储当前查看的分享完整数据 -->
      <div v-if="currentShare" class="share-detail">
        <el-descriptions :column="2" border>
          <!-- 两列描述列表，展示所有分享信息 -->
          <el-descriptions-item label="分享名称">{{ currentShare.shareName }}</el-descriptions-item>
          <el-descriptions-item label="分享码">{{ currentShare.shareCode }}</el-descriptions-item>
          <el-descriptions-item label="分享链接">{{ currentShare.shareUrl }}</el-descriptions-item>
          <el-descriptions-item label="过期时间">{{ formatExpireTime(currentShare.shareEndTime) }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="getStatusType(currentShare.shareStatus)">
              {{ getStatusText(currentShare.shareStatus) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDate(currentShare.createTime) }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onUnmounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Link, Key, CopyDocument, Share } from '@element-plus/icons-vue'
import { getShareList, createShare as createShareApi, cancelShare as cancelShareApi, getShareDetail } from '@/api/share'
import { getFileList } from '@/api/file'
import { useUserStore } from '@/stores/user'

// 响应式数据
const shareList = ref([])
const loading = ref(false)
const showCreateShareDialog = ref(false)
const showDetailDialog = ref(false)
const currentShare = ref(null)
const tableHeight = ref(400)
const shareFormRef = ref(null)
const loadingShareFiles = ref(false)
const userStore = useUserStore()

// 表单数据
const shareForm = ref({
  shareName: '',
  shareFileIds: [],
  shareType: 0,
  shareDayType: 7
})

const shareRules = {
  shareName: [
    { required: true, message: '请输入分享名称', trigger: 'blur' }
  ],
  shareFileIds: [
    { required: true, message: '请选择要分享的文件', trigger: 'change' }
  ]
}

const fileList = ref([])

/**
 * 加载可分享的文件列表（用户根目录下的所有文件）
 * async 标记：说明函数内有网络请求这类异步操作
 */
const loadShareFileList = async () => {
  // ========== 第一步：前置校验，确保拿到用户根目录ID ==========
  // 如果全局用户仓库里没有根目录ID（说明还没拉取过用户信息）
  if (!userStore.rootFileId) {
    // 调用接口获取用户信息，返回结果里包含用户网盘的根文件夹ID
    // await：等待这个接口请求完成，再继续往下执行代码
    await userStore.getUserInfoAction()
  }

  // 第二次兜底判断：如果调完用户信息接口，还是拿不到根目录ID（比如登录失效、token过期）
  if (!userStore.rootFileId) {
    // 直接把文件列表置空，终止函数，避免后面发无效请求报错
    fileList.value = []
    return
  }

  // ========== 第二步：开启加载状态 ==========
  // 对应页面上的加载转圈动画，告诉用户「正在加载中」
  loadingShareFiles.value = true

  try {
    // ========== 第三步：调用后端接口，获取文件列表数据 ==========
    // getFileList 是封装好的接口请求方法，传入两个查询参数
    // parentId：要查哪个文件夹下的文件，这里传根目录ID，只查根目录一层
    // fileTypes: '-1' 代表不过滤文件类型，所有类型的文件+文件夹都查
    const response = await getFileList({
      parentId: userStore.rootFileId,
      fileTypes: '-1'
    })

    // ========== 第四步：接口返回成功，格式化数据 ==========
    // 兼容两种成功标识：后端返回 code='SUCCESS' 或者 success=true 都算成功
    if (response?.code === 'SUCCESS' || response?.success) {
      // 兼容兜底：如果返回的data是数组就直接用，不是数组就给空数组，防止后续遍历报错
      const list = Array.isArray(response.data) ? response.data : []

      // 遍历后端返回的原始数据，改成前端页面需要的格式
      fileList.value = list.map(item => ({
        // 把后端的数字ID转成字符串：长数字JS会精度丢失，转字符串是前端常规操作
        fileId: String(item.id),
        // 文件名优化：如果是文件夹，前面加「[文件夹]」前缀，用户一眼就能区分文件和文件夹
        filename: item.folderFlag ? `[文件夹] ${item.filename}` : item.filename
      }))
      return // 数据处理完成，直接退出逻辑
    }

    // 接口返回业务失败（比如参数错误、无权限），文件列表置空
    fileList.value = []

  } catch (error) {
    // ========== 第五步：异常捕获（网络错误、接口500、断网等） ==========
    // 出错也把列表置空
    fileList.value = []
    // 弹出错误提示，告知用户加载失败
    ElMessage.error('加载可分享文件失败')

  } finally {
    // ========== 第六步：无论成功失败，最后一定会执行 ==========
    // 关闭加载状态，停止页面转圈动画
    loadingShareFiles.value = false
  }
}


// 计算表格高度
const calculateTableHeight = () => {
  const windowHeight = window.innerHeight
  const offset = 200 // 其他元素的高度
  tableHeight.value = windowHeight - offset
}

// 显示操作按钮
const showOperation = (row, event) => {
  const container = event.currentTarget
  const operationContent = container.querySelector('.share-operation-content')
  if (operationContent) {
    operationContent.classList.add('show')
  }
}

// 隐藏操作按钮
const hiddenOperation = (row, event) => {
  const container = event.currentTarget
  const operationContent = container.querySelector('.share-operation-content')
  if (operationContent) {
    operationContent.classList.remove('show')
  }
}

// 格式化过期时间
const formatExpireTime = (time) => {
  if (!time) return '永久有效'
  const date = new Date(time)
  return date.toLocaleDateString('zh-CN')
}

// 格式化日期
const formatDate = (date) => {
  if (!date) return ''
  return new Date(date).toLocaleString('zh-CN')
}

// 方法
/**
 * 加载当前用户创建的所有分享记录列表（页面主列表）
 * 展示内容：分享链接、有效期、提取码、包含的文件、创建时间等
 */
const loadShareList = async () => {
  // 开启加载动画，告诉用户正在加载
  loading.value = true

  try {
    // 调用后端接口：获取我创建的分享列表
    const response = await getShareList()
    // 接口返回业务成功
    if (response.code === 'SUCCESS') {
      // 把返回的数据赋值给页面列表变量，有数据就用数据，没有就给空数组防止报错
      shareList.value = response.data || []
    }
  } catch (error) {
    // 网络错误、接口报错等异常情况
    console.error('加载分享列表失败:', error)
    // 弹出错误提示
    ElMessage.error('加载分享列表失败')
  } finally {
    // 无论成功失败，最后都关闭加载动画
    loading.value = false
  }
}


const copyShareUrl = (share) => {
  navigator.clipboard.writeText(share.shareUrl).then(() => {
    ElMessage.success('分享链接已复制到剪贴板')
  }).catch(() => {
    ElMessage.error('复制失败')
  })
}

const copyShareCode = (share) => {
  navigator.clipboard.writeText(share.shareCode).then(() => {
    ElMessage.success('分享码已复制到剪贴板')
  }).catch(() => {
    ElMessage.error('复制失败')
  })
}

const getStatusType = (status) => {
  const typeMap = {
    0: 'success',
    1: 'warning',
    2: 'danger'
  }
  return typeMap[status] || 'info'
}

const getStatusText = (status) => {
  const textMap = {
    0: '正常',
    1: '即将过期',
    2: '已过期'
  }
  return textMap[status] || '未知'
}

/**
 * 查看分享详情弹窗
 * @param share 单条分享行数据对象
 */
const viewShareDetail = async (share) => {
  try {
    // 调用接口，根据分享id查询完整分享详情
    const response = await getShareDetail(share.id)
    // 业务请求成功
    if (response.code === 'SUCCESS') {
      // 把后端返回的详情赋值给全局变量，弹窗读取该数据渲染
      currentShare.value = response.data
      // 打开详情弹窗
      showDetailDialog.value = true
    }
  } catch (error) {
    // 网络/接口异常统一提示
    ElMessage.error('获取分享详情失败')
  }
}

/**
 * 分享续期（待开发占位方法）
 * @param share 单条分享行数据对象
 */
const extendShare = (share) => {
  // 临时提示，功能未完成
  ElMessage.info('续期功能开发中...')
}

/**
 * 取消分享，删除当前分享记录
 * @param share 单条分享行数据对象
 */
const cancelShare = async (share) => {
  try {
    // 弹出二次确认框，防止误操作
    await ElMessageBox.confirm('确定要取消这个分享吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })

    // 确认后调用取消分享接口，id转为字符串传给后端
    await cancelShareApi({ shareId: share.id.toString() })
    ElMessage.success('取消分享成功')
    // 刷新我的分享列表，移除已取消的数据
    loadShareList()
  } catch (error) {
    // 用户点击“取消”按钮时error值为cancel，这种情况不报错
    if (error !== 'cancel') {
      console.error('取消分享失败:', error)
      ElMessage.error('取消分享失败')
    }
  }
}

// 弹窗确定按钮绑定的创建分享业务方法
const createShare = async () => {
  try {
    // 1、表单校验：校验分享名称、必选项等规则
    if (shareFormRef.value) {
      await shareFormRef.value.validate()
    }

    // 2、处理选中文件ID数组，统一转为字符串格式传给后端
    const shareFileIds = Array.isArray(shareForm.value.shareFileIds)
        // 遍历数组，每个id转字符串
        ? shareForm.value.shareFileIds.map(item => String(item))
        // 无选中文件则为空数组
        : []

    // 3、校验：必须选择至少一个文件才能创建分享
    if (shareFileIds.length === 0) {
      ElMessage.warning('请选择要分享的文件')
      return // 终止后续逻辑，不发请求
    }

    // 4、组装后端接口需要的请求参数
    const data = {
      // 分享名称，去除首尾空格
      shareName: (shareForm.value.shareName || '').trim(),
      // 选中的文件id集合
      shareFileIds,
      // 分享类型转数字
      shareType: Number(shareForm.value.shareType),
      // 分享有效期天数转字符串
      shareDayType: String(shareForm.value.shareDayType)
    }

    // 5、调用分享创建接口（导入的接口函数createShareApi）
    const response = await createShareApi(data)

    // 6、接口请求成功且业务码为SUCCESS
    if (response.code === 'SUCCESS') {
      ElMessage.success('创建分享成功')
      // 关闭创建分享弹窗
      showCreateShareDialog.value = false

      // 重置表单所有字段，下次打开弹窗为空
      shareForm.value.shareName = ''
      shareForm.value.shareFileIds = []
      shareForm.value.shareType = 0
      shareForm.value.shareDayType = 7

      // 刷新我的分享列表，展示刚创建的分享
      loadShareList()
    } else {
      // 业务失败（参数错误/服务异常等），弹出后端返回提示
      ElMessage.error(response.message || '创建分享失败')
    }
  } catch (error) {
    // 7、捕获网络异常、请求报错等全部未知错误
    console.error('创建分享失败:', error)
    ElMessage.error('创建分享失败')
  }
}


// Vue 生命周期：组件DOM渲染到页面上之后，自动执行一次
onMounted(() => {
  // 1. 加载我创建的所有分享记录列表（页面主内容）
  loadShareList()
  // 2. 计算分享列表表格的高度，适配当前窗口
  calculateTableHeight()
  // 3. 监听浏览器窗口大小变化，窗口一变就重新算表格高度
  window.addEventListener('resize', calculateTableHeight)
})

// 监听「创建分享弹窗」的显示状态
// 第一个参数：要监听的响应式变量 showCreateShareDialog（true=弹窗打开，false=弹窗关闭）
// 第二个参数：状态变化时执行的回调函数，visible是变化后最新的值
watch(showCreateShareDialog, (visible) => {
  if (visible) {
    loadShareFileList()
  }
})

onUnmounted(() => {
  window.removeEventListener('resize', calculateTableHeight)
})
</script>

<style scoped>
.shares-container {
  padding: 0;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-header h2 {
  margin: 0;
  color: #333;
}

.header-actions {
  display: flex;
  gap: 10px;
}

.share-item {
  display: flex;
  align-items: center;
}

.share-icon {
  margin-right: 8px;
  font-size: 18px;
}

.share-name {
  color: #333;
}

.shares-table {
  width: 100% !important;
  table-layout: fixed;
}

:deep(.el-table) {
  border-radius: var(--border-radius);
  width: 100% !important;
  table-layout: fixed;
}

:deep(.el-table__body-wrapper) {
  overflow-x: auto;
}

:deep(.el-table__header-wrapper) {
  overflow: hidden;
}

:deep(.el-table th) {
  background: var(--bg-light);
  color: var(--text-primary);
  font-weight: 600;
}

:deep(.el-table td) {
  padding: 12px 0;
}

/* 分享URL单元格样式 */
.share-url-cell {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 12px;
  background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
  border-radius: 8px;
  border: 1px solid #e9ecef;
  transition: all 0.3s ease;
}

.share-url-cell:hover {
  background: linear-gradient(135deg, #e3f2fd 0%, #bbdefb 100%);
  border-color: #2196f3;
  box-shadow: 0 2px 8px rgba(33, 150, 243, 0.15);
}

.share-url-content {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-grow: 1;
  min-width: 0;
}

.share-url-text {
  font-weight: 500;
  color: #1976d2;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 13px;
}

.link-icon {
  color: #2196f3;
  font-size: 16px;
  flex-shrink: 0;
}

.copy-btn {
  flex-shrink: 0;
  border-radius: 6px;
  font-weight: 500;
  transition: all 0.2s ease;
}

.copy-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(33, 150, 243, 0.3);
}

/* 分享码显示样式 */
.share-code-display {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 8px 12px;
  background: linear-gradient(135deg, #fff3e0 0%, #ffe0b2 100%);
  border-radius: 8px;
  border: 1px solid #ffcc80;
  transition: all 0.3s ease;
  cursor: pointer;
}

.share-code-display:hover {
  background: linear-gradient(135deg, #ffe0b2 0%, #ffcc80 100%);
  border-color: #ff9800;
  box-shadow: 0 2px 8px rgba(255, 152, 0, 0.15);
  transform: translateY(-1px);
}

.share-code-text {
  font-weight: 600;
  color: #f57c00;
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 14px;
  letter-spacing: 1px;
  user-select: all;
}

.code-icon {
  color: #ff9800;
  font-size: 16px;
  flex-shrink: 0;
}

/* 分享行容器样式 */
.share-row-container {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
}

.share-name-content {
  flex: 1;
  display: flex;
  align-items: center;
}

.share-operation-content {
  position: relative;
  opacity: 0;
  transition: opacity 0.3s ease;
  pointer-events: none;
}

.share-operation-content.show {
  opacity: 1;
  pointer-events: auto;
}

.pan-share-operations {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  background: rgba(255, 255, 255, 0.95);
  padding: 4px 8px;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  backdrop-filter: blur(10px);
  z-index: 10;
  position: static !important;
  right: auto !important;
  top: auto !important;
}

.pan-share-operations .el-button {
  margin: 0;
  flex-shrink: 0;
  transition: all 0.2s ease;
}

.pan-share-operations .el-button:hover {
  transform: scale(1.1);
}

.share-detail {
  padding: 20px;
}

.share-detail .el-descriptions {
  margin-bottom: 20px;
}

.expire-time {
  color: var(--text-secondary);
  font-size: 0.9em;
}

/* 卡片样式 */
.el-card {
  border: none;
  box-shadow: var(--shadow-light);
  transition: all 0.3s ease;
}

.el-card:hover {
  box-shadow: var(--shadow-medium);
}

/* 响应式设计 */
@media (max-width: 1200px) {
  .pan-toolbar {
    flex-direction: column;
    gap: 10px;
    align-items: flex-start;
  }
  
  .toolbar-right .pan-btn-primary {
    width: 100%;
    margin-left: 0;
  }

  .shares-table-container {
    overflow-x: visible;
  }
  
  :deep(.el-table) {
    font-size: 13px;
  }
  
  :deep(.el-table th),
  :deep(.el-table td) {
    padding: 10px 6px;
  }
}

@media (max-width: 992px) {
  .pan-toolbar {
    flex-direction: column;
    gap: 10px;
    align-items: flex-start;
  }
  
  .toolbar-right .pan-btn-primary {
    width: 100%;
    margin-left: 0;
  }

  .shares-table-container {
    overflow-x: visible;
  }
  
  :deep(.el-table) {
    font-size: 12px;
  }
  
  :deep(.el-table th),
  :deep(.el-table td) {
    padding: 8px 4px;
  }
}

@media (max-width: 768px) {
  .pan-main-content {
    padding: 10px;
  }
  
  .pan-toolbar {
    flex-direction: column;
    gap: 10px;
    align-items: flex-start;
  }
  
  .toolbar-right .pan-btn-primary {
    width: 100%;
    margin-left: 0;
  }

  .shares-table-container {
    overflow-x: visible;
  }

  :deep(.el-table) {
    font-size: 12px;
  }
  
  :deep(.el-table th),
  :deep(.el-table td) {
    padding: 8px 4px;
  }
  
  .share-row-container {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
  
  .share-operation-content {
    align-self: flex-end;
  }
  
  /* 移动端分享链接和分享码优化 */
  .share-url-cell {
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
    padding: 10px;
  }
  
  .share-url-content {
    justify-content: center;
  }
  
  .copy-btn {
    align-self: center;
  }
  
  .share-url-text {
    font-size: 12px;
  }
  
  .share-code-display {
    padding: 8px;
  }
  
  .share-code-text {
    font-size: 12px;
  }
}
</style> 
