<template>
  <!-- 回收站页面根容器 -->
  <div class="recycle-container">
    <!-- 页面头部：标题 + 批量操作按钮 -->
    <div class="page-header">
      <h2>回收站</h2>
      <div class="header-actions">
        <!-- 批量还原按钮：选中文件为空时禁用 -->
        <el-button @click="batchRestore" :disabled="selectedFiles.length === 0">
          <el-icon><RefreshLeft /></el-icon>
          批量还原
        </el-button>
        <!-- 批量彻底删除按钮：选中文件为空时禁用 -->
        <el-button type="danger" @click="batchDelete" :disabled="selectedFiles.length === 0">
          <el-icon><Delete /></el-icon>
          彻底删除
        </el-button>
      </div>
    </div>

    <!-- 表格卡片容器 -->
    <el-card>
<!--      :data="recycleList"       &lt;!&ndash; 表格数据源：回收站文件列表 &ndash;&gt;-->
<!--      style="width: 100%"-->
<!--      v-loading="loading"       &lt;!&ndash; 加载中状态 &ndash;&gt;-->
<!--      @selection-change="handleSelectionChange"  &lt;!&ndash; 表格选择项变化事件 &ndash;&gt;-->
      <el-table 
        :data="recycleList" 
        style="width: 100%" 
        v-loading="loading"
        @selection-change="handleSelectionChange"
      >
        <!-- 表格选择列（复选框） -->
        <el-table-column type="selection" width="55" />
        <!-- 文件名列（自定义模板） -->
        <el-table-column label="文件名" min-width="300">
          <template #default="{ row }">
            <!-- 文件行容器：鼠标移入/移出控制操作按钮显隐 -->
            <div class="file-row-container" 
                 @mouseenter="showOperation(row, $event)" 
                 @mouseleave="hiddenOperation(row, $event)">
              <!-- 文件名 + 图标区域 -->
              <div class="file-name-content">
                <el-icon class="file-icon" :color="getFileIconColor(row)">
                  <!-- 动态组件：根据文件类型渲染不同图标 -->
                  <component :is="getFileIcon(row)" />
                </el-icon>
                <span class="file-name">{{ row.filename }}</span>
              </div>
              <!-- 文件操作按钮区域（默认隐藏，hover 显示） -->
              <div class="file-operation-content">
                <div class="pan-file-operations">
                  <!-- 单个还原按钮 -->
                  <el-tooltip class="item" effect="light" content="还原" placement="top">
                    <el-button icon="RefreshLeft" type="success" size="small" circle @click.stop="restoreFile(row)"></el-button>
                  </el-tooltip>
                  <!-- 单个彻底删除按钮 -->
                  <el-tooltip class="item" effect="light" content="彻底删除" placement="top">
                    <el-button icon="Delete" type="danger" size="small" circle @click.stop="deleteFile(row)"></el-button>
                  </el-tooltip>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>
        
        <el-table-column prop="fileSizeDesc" label="大小" width="100" />
        <el-table-column label="删除时间" width="180">
          <template #default="{ row }">
            {{ formatDateTime(row.gmtModified) }}
          </template>
        </el-table-column>
        

      </el-table>
    </el-card>
    
    <!-- 空状态 -->
    <el-empty v-if="recycleList.length === 0 && !loading" description="回收站为空" />
  </div>
</template>

<script setup>
// ===================== 1. 依赖导入 =====================
// 从 Vue 导入组合式 API
// ref：创建响应式数据，数据变化时页面自动更新
// onMounted：生命周期钩子，组件挂载到页面后自动执行
import { ref, onMounted } from 'vue'

// 从 Element Plus 导入交互组件
// ElMessage：顶部消息提示（成功/失败/警告）
// ElMessageBox：确认弹窗（二次确认危险操作）
import { ElMessage, ElMessageBox } from 'element-plus'

// 从项目 api 目录导入回收站相关的后端接口方法
// getRecycleList：获取回收站文件列表
// restoreFiles：还原文件（支持单个/批量）
// deleteRecycleFiles：彻底删除文件（支持单个/批量）
import { getRecycleList, restoreFiles, deleteRecycleFiles } from '@/api/recycle'


// ===================== 2. 响应式数据定义 =====================
// 页面加载状态：true 显示加载动画，false 关闭加载
// 作用：请求过程中禁止重复操作，给用户加载反馈
const loading = ref(false)

// 回收站文件列表：表格的核心数据源
// 存储所有回收站文件的完整信息（id、文件名、大小、删除时间等）
const recycleList = ref([])

// 选中的文件集合
// 存储表格中被勾选的所有文件对象，用于批量还原、批量删除操作
const selectedFiles = ref([])


// ===================== 3. 核心业务方法 =====================

/**
 * 加载回收站文件列表
 * 页面初始化、操作完成后都会调用，用于刷新最新数据
 */
const loadRecycleList = async () => {
  loading.value = true // 开启加载状态
  try {
    // 调用后端接口，异步等待返回结果
    const response = await getRecycleList()

    // 接口返回成功时，更新页面数据
    if (response?.success) {
      recycleList.value = response.data || [] // 赋值列表数据
      selectedFiles.value = [] // 刷新列表后清空选中状态，避免残留旧数据
    }
  } catch (error) {
    // 接口请求失败的错误处理
    console.error('加载回收站列表失败:', error)
    ElMessage.error(error?.message || '加载回收站列表失败') // 弹出错误提示
  } finally {
    // 无论成功还是失败，最后都关闭加载状态
    loading.value = false
  }
}

/**
 * 表格选中状态变化的回调函数
 * @param {Array} selection - Element Plus 表格自动传入的参数：当前所有选中的行对象
 * 说明：表格内部自己维护选中状态，通过这个事件把结果同步给我们的业务代码
 */
const handleSelectionChange = (selection) => {
  // 将表格的选中结果，同步到我们自己的响应式变量
  // 后续批量按钮禁用、批量操作都依赖这个变量
  selectedFiles.value = selection
}

/**
 * 时间格式化工具函数
 * @param {String|Number} value - 原始时间戳/时间字符串
 * @returns {String} 格式化后的中文时间，异常情况返回占位符
 */
const formatDateTime = (value) => {
  if (!value) return '--' // 空值显示短横线占位
  const date = new Date(value)
  // 非法时间直接返回原值，避免页面显示 Invalid Date
  if (Number.isNaN(date.getTime())) return value
  // 格式化为中文本地时间，形如：2024/5/20 14:30:00
  return date.toLocaleString('zh-CN')
}

/**
 * 鼠标移入文件行时，显示该行的操作按钮
 * @param {Object} row - 当前行的数据（当前未直接使用）
 * @param {Event} event - 鼠标事件对象，用于获取当前DOM元素
 * 说明：通过操作 DOM 类名，配合 CSS 实现 hover 显隐效果
 */
const showOperation = (row, event) => {
  // 1. 获取当前触发事件的行容器
  const container = event.currentTarget

  // 2. 在当前行容器里，找到操作按钮的DOM元素
  const operationContent = container.querySelector('.file-operation-content')

  // 3. 如果找到了这个元素，就给它加上 show 类名
  // classList.add('show')：给这个元素添加一个叫 show 的 class 类名
//   真正控制显隐的，是 CSS 样式，对应你代码里的：
//   /* 加上 show 类后：不透明、可点击 */
// .file-operation-content.show
  if (operationContent) {
    operationContent.classList.add('show')
  }
}


/**
 * 鼠标移出文件行时，隐藏操作按钮
 * 逻辑和 showOperation 相反，移除 show 类
 */
const hiddenOperation = (row, event) => {
  const container = event.currentTarget
  const operationContent = container.querySelector('.file-operation-content')
  if (operationContent) {
    operationContent.classList.remove('show')
  }
}

/**
 * 根据文件类型，返回对应的图标组件名
 * @param {Object} file - 文件数据对象
 * @returns {String} Element Plus 图标组件名
 */
const getFileIcon = (file) => {
  // 文件夹单独返回文件夹图标
  if (file.folderFlag) return 'Folder'

  // 文件类型映射表：后端返回的数字类型 → 前端图标名
  const typeMap = {
    7: 'Picture',    // 图片
    9: 'VideoPlay',  // 视频
    3: 'Document',   // 各类文档统一用文档图标
    4: 'Document',
    5: 'Document',
    6: 'Document',
    10: 'Document',
    11: 'Document',
    12: 'Document'
  }

  // 匹配不到的未知类型，默认返回文档图标
  return typeMap[file.fileType] || 'Document'
}

/**
 * 根据文件类型，返回图标的颜色
 * @param {Object} file - 文件数据对象
 * @returns {String} 十六进制颜色值
 * 说明：不同类型用不同颜色，提升视觉辨识度
 */
const getFileIconColor = (file) => {
  // 文件夹固定蓝色
  if (file.folderFlag) return '#409eff'

  // 颜色映射表，和图标类型一一对应
  const colorMap = {
    7: '#67c23a', // 图片：绿色
    9: '#e6a23c', // 视频：橙色
    3: '#f56c6c', // 文档：红色
    4: '#f56c6c',
    5: '#f56c6c',
    6: '#f56c6c',
    10: '#f56c6c',
    11: '#f56c6c',
    12: '#f56c6c'
  }

  // 未知类型默认灰色
  return colorMap[file.fileType] || '#909399'
}

/**
 * 单个文件还原操作
 * @param {Object} file - 要还原的文件对象
 */
const restoreFile = async (file) => {
  try {
    // 弹出二次确认弹窗，防止用户误点
    await ElMessageBox.confirm('确定要还原这个文件吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })

    // 用户点击确定后，调用还原接口，传入当前文件的 id
    await restoreFiles({ fileIds: [String(file.id)] })
    ElMessage.success('还原成功') // 成功提示
    loadRecycleList() // 重新加载列表，刷新页面数据
  } catch (error) {
    // 用户点击「取消」会抛出 'cancel'，这种情况不报错，只处理真正的接口失败
    if (error !== 'cancel') {
      ElMessage.error('还原失败')
    }
  }
}

/**
 * 单个文件彻底删除操作
 * 逻辑结构和还原完全一致，只是接口、提示文案不同
 */
const deleteFile = async (file) => {
  try {
    // 危险操作加强提示，强调「不可恢复」
    await ElMessageBox.confirm('确定要彻底删除这个文件吗？此操作不可恢复！', '警告', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })

    await deleteRecycleFiles({ fileIds: [String(file.id)] })
    ElMessage.success('删除成功')
    loadRecycleList()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

/**
 * 批量还原选中的文件
 */
const batchRestore = async () => {
  // 前置校验：没有选中文件时直接提示，不发起请求
  if (selectedFiles.value.length === 0) {
    ElMessage.warning('请选择要还原的文件')
    return
  }

  try {
    // 二次确认，显示选中的文件数量
    await ElMessageBox.confirm(`确定要还原选中的 ${selectedFiles.value.length} 个文件吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })

    // 从选中的文件数组中，提取所有文件的 id，组成 id 数组传给后端
    // 把「选中的文件对象数组」，加工成「只包含文件 ID 的字符串数组」，传给后端接口用。
    const fileIds = selectedFiles.value.map(f => String(f.id))
    await restoreFiles({ fileIds }) // 调用批量还原接口
    ElMessage.success('批量还原成功')
    loadRecycleList() // 刷新列表
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('批量还原失败')
    }
  }
}

/**
 * 批量彻底删除选中的文件
 * 逻辑和批量还原一致，只是接口、提示文案不同
 */
const batchDelete = async () => {
  if (selectedFiles.value.length === 0) {
    ElMessage.warning('请选择要删除的文件')
    return
  }

  try {
    await ElMessageBox.confirm(`确定要彻底删除选中的 ${selectedFiles.value.length} 个文件吗？此操作不可恢复！`, '警告', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    // 把「选中的文件对象数组」，加工成「只包含文件 ID 的字符串数组」，传给后端接口用。
    const fileIds = selectedFiles.value.map(f => String(f.id))
    await deleteRecycleFiles({ fileIds })
    ElMessage.success('批量删除成功')
    loadRecycleList()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('批量删除失败')
    }
  }
}


// ===================== 4. 生命周期钩子 =====================
// 组件挂载到页面后，自动执行：加载回收站列表
onMounted(() => {
  loadRecycleList()
})
</script>


<style scoped>
.recycle-container {
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

/* 文件行容器样式 */
.file-row-container {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
}

.file-name-content {
  flex: 1;
  display: flex;
  align-items: center;
}

.file-operation-content {
  position: relative;
  opacity: 0;
  transition: opacity 0.3s ease;
  pointer-events: none;
}

.file-operation-content.show {
  opacity: 1;
  pointer-events: auto;
}

.pan-file-operations {
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

.pan-file-operations .el-button {
  margin: 0;
  flex-shrink: 0;
  transition: all 0.2s ease;
}

.pan-file-operations .el-button:hover {
  transform: scale(1.1);
}

.file-icon {
  margin-right: 8px;
  font-size: 18px;
}

.file-name {
  color: #333;
}
</style> 
