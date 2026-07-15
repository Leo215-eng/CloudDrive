<!-- 首页最外层容器，pan-main-content是布局统一类，会自动适配Layout右侧内容区域 -->
<template>
  <div class="home-container pan-main-content">
    <!-- ElementPlus栅格行 el-row：一行容器，gutter=12 代表内部每一列之间左右间距12px -->
    <el-row :gutter="12">
      <!-- el-col 栅格列，页面总宽度固定24份，span=6 占6/24=1/4宽度，一行4个卡片均分 -->
      <el-col :span="6">
        <!-- el-card ElementPlus卡片组件，自带白色背景、圆角阴影，用来包裹统计数字 -->
        <el-card class="stat-card">
          <!-- 卡片内部flex布局容器，放图标+文字 -->
          <div class="stat-content">
            <!-- el-icon图标组件，Folder文件夹图标，color自定义蓝色 -->
            <el-icon class="stat-icon" color="#409eff"><Folder /></el-icon>
            <div class="stat-info">
              <!-- {{ }} Vue插值语法，把JS变量stats.totalFiles渲染到页面，stats是后端返回的文件总数 -->
              <div class="stat-number">{{ stats.totalFiles }}</div>
              <div class="stat-label">总文件数</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <!-- 第二张统计卡片：图片数量 -->
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <el-icon class="stat-icon" color="#67c23a"><Picture /></el-icon>
            <div class="stat-info">
              <div class="stat-number">{{ stats.images }}</div>
              <div class="stat-label">图片文件</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <!-- 第三张统计卡片：视频数量 -->
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <el-icon class="stat-icon" color="#e6a23c"><VideoPlay /></el-icon>
            <div class="stat-info">
              <div class="stat-number">{{ stats.videos }}</div>
              <div class="stat-label">视频文件</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <!-- 第四张统计卡片：文档数量 -->
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <el-icon class="stat-icon" color="#f56c6c"><Document /></el-icon>
            <div class="stat-info">
              <div class="stat-number">{{ stats.documents }}</div>
              <div class="stat-label">文档文件</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
    <!-- 第二行栅格，margin-top:12px 和上面统计卡片拉开上下距离 -->
    <el-row :gutter="12" style="margin-top: 12px;">
      <!-- span=12 占一半宽度，左边放【最近上传表格】 -->
      <el-col :span="12">
        <el-card>
          <!-- #header 插槽：ElementPlus卡片专属头部插槽，自定义卡片标题区域 -->
          <template #header>
            <div class="card-header">
              <span>最近上传</span>
            </div>
          </template>
          <!-- v-if Vue条件渲染：如果最近上传文件数组长度等于0，显示空提示 -->
          <div v-if="recentFiles.length === 0" class="empty-state">
            <!-- el-empty ElementPlus空状态组件，无数据时展示友好文字 -->
            <el-empty description="暂无最近上传的文件" />
          </div>
          <!-- v-else 与v-if配对：有文件数据时渲染表格 -->
          <!-- :data 动态绑定表格数据源，recentFiles是后端返回的文件数组 -->
          <el-table v-else :data="recentFiles" style="width: 100%">
            <!-- 表格列：prop对应数组对象字段filename，label是表头文字，min-width最小宽度自适应 -->
            <el-table-column prop="filename" label="文件名" min-width="200" />
            <el-table-column prop="fileSizeDesc" label="大小" width="100" />
            <!-- 自定义时间列，不直接展示原始时间戳，需要格式化处理 -->
            <el-table-column label="上传时间" width="180">
              <!-- #default 默认插槽，{row} 代表当前这一行完整数据对象 -->
              <template #default="{ row }">
                <!-- 调用JS方法formatDateTime，把row.updateTime原始时间转为中文可读时间 -->
                <span>{{ formatDateTime(row.updateTime) }}</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <!-- 右侧半宽卡片：我的分享列表 -->
      <el-col :span="12">
        <el-card>
          <!-- 卡片头部标题插槽 -->
          <template #header>
            <div class="card-header">
              <span>我的分享</span>
            </div>
          </template>


          <!-- 无分享数据时展示空提示 -->
          <div v-if="recentShares.length === 0" class="empty-state">
            <el-empty description="暂无分享文件" />
          </div>
          <!-- 存在分享数据渲染表格 -->
          <el-table v-else :data="recentShares" style="width: 100%">
            <el-table-column prop="shareName" label="分享名称" min-width="220">
              <!-- 自定义单元格内容 -->
              <template #default="{ row }">
                <!-- 整行容器，绑定鼠标移入、移出事件 -->
                <!-- @mouseenter 鼠标悬浮进入，触发showOperation方法，$event传递鼠标事件对象 -->
                <!-- @mouseleave 鼠标离开，隐藏复制按钮 -->
                <div
                  class="share-row-container"
                  @mouseenter="showOperation($event)"
                  @mouseleave="hiddenOperation($event)"
                >
                  <!-- 左侧分享图标+分享名称 -->
                  <div class="share-name-content">
                    <el-icon class="share-icon" color="#409eff">
                      <Share />
                    </el-icon>
                    <span class="share-name">{{ row.shareName }}</span>
                  </div>
                  <!-- 右侧复制按钮容器，默认透明隐藏，悬浮才显示 -->
                  <div class="share-operation-content">
                    <!-- el-tooltip 鼠标悬浮提示框，content提示文字，placement提示框在上 -->
                    <el-tooltip effect="light" content="复制链接" placement="top">
                      <!-- 圆形小按钮，点击触发复制方法，把当前行完整数据row传给函数 -->
                      <el-button
                        :icon="CopyDocument"
                        type="primary"
                        size="small"
                        circle
                        @click="copyShareUrl(row)"
                      />
                    </el-tooltip>
                  </div>
                </div>
              </template>
            </el-table-column>
            <!-- 过期时间列 -->
            <el-table-column label="过期时间" width="180">
              <template #default="{ row }">
                <!-- 第二个参数是备用文字：时间为空就显示永久有效 -->
                <span>{{ formatDateTime(row.shareEndTime, '永久有效') }}</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
// 导入Vue内置工具API
// onMounted：页面渲染完成后自动执行函数；ref：创建响应式变量（变量变页面自动更新）；watch：监听变量变化
import { onMounted, ref, watch } from 'vue'
// ElementPlus消息提示：弹出轻提示文字（成功/失败/警告）
import { ElMessage } from 'element-plus'
// 导入页面所有用到的图标
import { CopyDocument, Document, Folder, Picture, Share, VideoPlay } from '@element-plus/icons-vue'
// 导入后端接口：获取首页统计、最近文件
import { getHomeOverview } from '@/api/file'
// 导入后端接口：获取我的分享列表
import { getShareList } from '@/api/share'
// useRoute：获取当前页面路由信息（浏览器地址）
import { useRoute } from 'vue-router'

// ========== 响应式数据定义（页面所有展示数据，来自后端接口） ==========
// stats 存储四类文件统计数字，ref包裹后修改值页面会自动刷新数字
const stats = ref({
  totalFiles: 0,   // 总文件数量
  images: 0,       // 图片数量
  videos: 0,       // 视频数量
  documents: 0     // 文档数量
})

// 最近上传文件数组，默认空数组，接口返回后填充数据渲染表格
const recentFiles = ref([])
// 我的分享数组，默认空数组
const recentShares = ref([])
// 拿到当前路由对象，读取浏览器地址信息
const route = useRoute()

// ========== 通用工具函数（处理数据、格式化，多处复用） ==========
/**
 * 安全转数字工具
 * 作用：后端返回数据可能是字符串、空值，转数字失败返回0，页面不会显示NaN乱码
 * @param value 后端原始数据
 * @returns 合法数字，转换失败返回0
 */
const toNumber = (value) => {
  const parsed = Number(value)
  // 判断是否是正常数字，是就返回，否则返回0
  return Number.isFinite(parsed) ? parsed : 0
}

/**
 * 时间解析工具
 * 作用：统一处理后端返回的时间字符串/时间戳，转为标准Date日期对象
 * @param value 原始时间值
 * @returns Date对象 / null（无时间返回空）
 */
const parseDate = (value) => {
  // 无数据直接返回空
  if (!value) return null
  // 本身是日期对象直接返回
  if (value instanceof Date) return value
  // 字符串转日期
  const date = new Date(value)
  // 日期合法返回，不合法返回null
  if (!Number.isNaN(date.getTime())) return date
  return null
}

/**
 * 时间格式化函数（页面展示可读中文时间）
 * @param value 原始时间
 * @param emptyText 无时间时显示的默认文字
 * @returns 格式化后的时间字符串
 */
const formatDateTime = (value, emptyText = '--') => {
  const date = parseDate(value)
  // 无时间返回备用文字
  if (!date) return emptyText
  // 转为中文本地时间格式 2026/6/29 14:30:00
  return date.toLocaleString('zh-CN')
}

/**
 * 数组按最新时间倒序排序
 * 作用：新上传/新分享的内容排在表格最上方
 * @param items 文件/分享数组
 * @returns 排序后的新数组
 */
const sortByRecent = (items) => {
  // [...items] 复制一份数组，不修改原始数据
  return [...items].sort((a, b) => {
    // 兼容多种后端返回的时间字段
    const left = parseDate(a.createTime || a.gmtCreate || a.shareEndTime)?.getTime() || 0
    const right = parseDate(b.createTime || b.gmtCreate || b.shareEndTime)?.getTime() || 0
    // 大数放前面，实现倒序（最新在前）
    return right - left
  })
}

// ========== 后端接口请求函数 ==========
/**
 * 请求首页统计、最近上传文件接口
 */
const loadOverview = async () => {
  // 调用后端API，await等待接口返回结果
  const response = await getHomeOverview()
  // 后端约定code=SUCCESS代表请求成功，失败抛出错误
  if (response.code !== 'SUCCESS') {
    throw new Error(response.message || '加载首页概览失败')
  }
  // 取出接口返回数据，为空默认空对象
  const data = response.data || {}
  // 给统计数字赋值，经过toNumber防止乱码
  stats.value = {
    totalFiles: toNumber(data.totalFiles),
    images: toNumber(data.images),
    videos: toNumber(data.videos),
    documents: toNumber(data.documents)
  }
  // 赋值最近上传文件，判断是否为数组防止报错
  recentFiles.value = Array.isArray(data.recentFiles) ? data.recentFiles : []
}

/**
 * 请求我的分享列表接口
 */
const loadShares = async () => {
  const response = await getShareList()
  if (response.code !== 'SUCCESS') {
    throw new Error(response.message || '加载分享列表失败')
  }
  const list = Array.isArray(response.data) ? response.data : []
  // 按时间倒序，只取前5条展示在页面
  recentShares.value = sortByRecent(list).slice(0, 5)
}

/**
 * 统一加载首页所有数据（同时请求两个接口）
 * Promise.allSettled：两个接口同时发起，一个失败不会阻断另一个，容错性强
 */
const loadHomeData = async () => {
  // 并行执行两个接口，接收两个请求结果
  const [overviewResult, shareResult] = await Promise.allSettled([loadOverview(), loadShares()])
  // 如果统计接口请求失败：打印控制台日志，页面弹出错误提示
  if (overviewResult.status === 'rejected') {
    console.error('加载首页概览失败:', overviewResult.reason)
    ElMessage.error('加载首页概览失败')
  }
  // 如果分享接口请求失败
  if (shareResult.status === 'rejected') {
    console.error('加载分享数据失败:', shareResult.reason)
    ElMessage.error('加载分享数据失败')
  }
}

// ========== 页面交互事件函数（鼠标点击、悬浮） ==========
/**
 * 复制分享链接
 * @param share 当前行分享完整数据对象
 */
const copyShareUrl = async (share) => {
  // 没有链接直接弹窗警告，终止执行
  if (!share?.shareUrl) {
    ElMessage.warning('当前分享没有可复制的链接')
    return
  }
  try {
    // 浏览器原生剪贴板API，把链接写入剪贴板
    await navigator.clipboard.writeText(share.shareUrl)
    ElMessage.success('分享链接已复制到剪贴板')
  } catch (error) {
    // 复制异常（浏览器权限限制）弹出错误提示
    ElMessage.error('复制失败')
  }
}

/**
 * 鼠标移入分享行：添加show样式类，显示复制按钮
 */
const showOperation = (event) => {
  // event.currentTarget：当前鼠标悬浮的div元素，添加class
  event.currentTarget.classList.add('show')
}

/**
 * 鼠标移出分享行：移除show样式类，隐藏复制按钮
 */
const hiddenOperation = (event) => {
  event.currentTarget.classList.remove('show')
}

// ========== 页面生命周期、路由监听 ==========
/**
 * onMounted：页面初次渲染完成后自动执行
 * 原理：页面加载完毕立刻拉取后端数据，填充页面表格、统计数字
 */
onMounted(() => {
  loadHomeData()
})

/**
 * watch 监听路由地址变化
 * 作用：从别的页面切回首页时，重新刷新最新文件/分享数据
 * 监听route.fullPath完整浏览器地址，地址变化触发回调
 */
watch(
    () => route.fullPath,
    (newPath) => {
      // 只有当前地址是首页 "/" 才重新加载数据
      if (newPath === '/') {
        loadHomeData()
      }
    }
)
</script>


<style scoped>
.home-container {
  padding: 0;
}

.stat-card {
  margin-bottom: 12px;
  border-radius: var(--border-radius);
  box-shadow: var(--shadow-light);
  transition: all 0.3s ease;
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-medium);
}

.stat-content {
  display: flex;
  align-items: center;
  padding: 12px;
}

.stat-icon {
  font-size: 40px;
  margin-right: 16px;
}

.stat-info {
  flex: 1;
}

.stat-number {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 4px;
}

.stat-label {
  color: var(--text-secondary);
  font-size: 14px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  color: var(--text-primary);
}

.empty-state {
  padding: 40px 0;
}

:deep(.el-card) {
  border-radius: var(--border-radius);
  box-shadow: var(--shadow-light);
  transition: all 0.3s ease;
}

:deep(.el-card:hover) {
  box-shadow: var(--shadow-medium);
}

:deep(.el-card__header) {
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-light);
}

:deep(.el-table) {
  border-radius: var(--border-radius);
}

:deep(.el-table th) {
  background: var(--bg-light);
  color: var(--text-primary);
  font-weight: 600;
  padding: 6px 0;
}

:deep(.el-table td) {
  padding: 6px 0;
}

.share-row-container {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.share-name-content {
  display: flex;
  align-items: center;
  flex: 1;
}

.share-icon {
  margin-right: 8px;
  font-size: 16px;
}

.share-name {
  color: var(--text-primary);
  font-size: 14px;
}

.share-operation-content {
  opacity: 0;
  transition: opacity 0.2s ease;
  margin-left: 8px;
}

.share-row-container.show .share-operation-content {
  opacity: 1;
}

@media (max-width: 1200px) {
  .stat-content {
    padding: 12px;
  }

  .stat-icon {
    font-size: 36px;
    margin-right: 12px;
  }

  .stat-number {
    font-size: 24px;
  }
}

@media (max-width: 992px) {
  .stat-content {
    padding: 8px;
  }

  .stat-icon {
    font-size: 32px;
    margin-right: 8px;
  }

  .stat-number {
    font-size: 20px;
  }
}

@media (max-width: 768px) {
  .stat-content {
    padding: 8px;
  }

  .stat-icon {
    font-size: 28px;
    margin-right: 8px;
  }

  .stat-number {
    font-size: 18px;
  }

  .stat-label {
    font-size: 12px;
  }
}
</style>
