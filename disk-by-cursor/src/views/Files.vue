<!-- 文件页面最外层容器，全部文件/图片/文档/视频/音乐共用这个页面 -->
<template>
  <div class="pan-main-content files-page">
    <!-- 页面顶部区域：面包屑导航 + 操作工具栏 -->
    <section class="page-top">
      <!-- 面包屑导航容器，用来展示当前所在文件夹层级 -->
      <div class="pan-breadcrumb">
        <!-- ElementPlus面包屑组件，separator="/" 设置层级之间分隔符为斜杠 -->
       <el-breadcrumb separator="/">
         <!-- 根目录面包屑项，点击返回最外层根文件夹 -->
<!--         传空是为了"回到根目录"，这是一种设计约定-->
         <el-breadcrumb-item class="breadcrumb-item" @click="navigateToFolder('')">
           <div class="breadcrumb-content">
             <el-icon class="breadcrumb-icon"><Folder /></el-icon>
             <span class="breadcrumb-text">根目录</span>
           </div>
         </el-breadcrumb-item>

         <!-- 循环渲染中间层级文件夹，fileStore.breadcrumbs 是仓库存储的文件夹路径数组 -->
         <!-- v-for 遍历每一层文件夹，:key绑定唯一id防止渲染报错 -->
         <el-breadcrumb-item
           v-for="item in fileStore.breadcrumbs"
           :key="item.id"
           class="breadcrumb-item"
           @click="navigateToFolder(item.id)"
         >
           <div class="breadcrumb-content">
             <el-icon class="breadcrumb-icon"><Folder /></el-icon>
             <!-- item.name 文件夹名称，插值渲染到页面 -->
            <span class="breadcrumb-text">{{ item.name }}</span>
          </div>
        </el-breadcrumb-item>
      </el-breadcrumb>
    </div>

      <!-- 工具栏外层容器，分为左侧上传/新建文件夹、右侧搜索/批量操作按钮 -->
    <div class="toolbar-shell">
      <!-- 工具栏左侧：上传、新建文件夹 -->
    <div class="toolbar-left">
      <!-- 自定义上传文件组件，封装了文件上传逻辑 -->
      <UploadButton />
      <!-- 新建文件夹按钮，点击触发创建文件夹弹窗/逻辑 -->
      <el-button class="create-folder-btn" @click="createFolder">
        <el-icon><FolderAdd /></el-icon>
        新建文件夹
      </el-button>
    </div>

      <!-- 工具栏右侧：搜索框、清空搜索、批量删除、批量下载 -->
    <div class="toolbar-right">
      <!-- 搜索输入框，v-model双向绑定搜索关键词 -->
      <!--  clearable  自带一键清空输入框按钮 ，输入框右侧会出现一个 “×” 图标（鼠标悬浮时显示）
      点击 × → 一键清空 v-model 绑定的 searchKeyword 变量。-->
<!--      @keyup.enter="handleSearch"  按下回车键触发搜索-->
      <el-input
        v-model="searchKeyword"
        class="search-input"
        clearable
        placeholder="搜索当前类型下的文件名，回车开始"
        @keyup.enter="handleSearch"
      >
        <!-- #append 输入框尾部插槽，放置搜索按钮 -->
        <template #append>
          <!-- 搜索按钮，searchLoading控制加载转圈动画 -->
          <el-button :loading="searchLoading" @click="handleSearch">
            <el-icon><Search /></el-icon>
          </el-button>
        </template>
      </el-input>

      <!-- v-if 搜索模式才显示清空搜索按钮，退出搜索恢复全部文件，选中文件数量大于0才显示 -->
      <el-button v-if="isSearchMode" @click="clearSearch">清空搜索</el-button>


      <!-- 批量删除按钮：选中文件数量大于0才显示 -->
      <el-button
        v-if="selectedFiles.length > 0"
        type="danger"
        @click="batchDelete"
      >
        <el-icon><Delete /></el-icon>
        批量删除
      </el-button>
      <!-- 批量下载按钮：选中文件数量大于0才显示 -->
      <el-button
        v-if="selectedFiles.length > 0"
        type="success"
        @click="batchDownload"
      >
        <el-icon><Download /></el-icon>
        批量下载
      </el-button>
    </div>
    </div>
  </section>

    <!-- 文件表格区域：展示当前文件夹下所有文件/文件夹 -->
  <section class="file-table-shell">
<!--    v-loading="loading"  &lt;!&ndash; 加载中遮罩，请求文件列表时转圈等待 &ndash;&gt;-->
<!--    :data="fileStore.files"  &lt;!&ndash; 表格数据源，仓库里的文件数组 &ndash;&gt;-->
<!--    :height="tableHeight"  &lt;!&ndash; 表格固定高度，超出自动滚动 &ndash;&gt;-->
<!--    class="file-table pan-table"-->
<!--    tooltip-effect="dark"  &lt;!&ndash; 鼠标悬浮提示深色样式 &ndash;&gt;-->
<!--    empty-text="当前目录暂无文件"  &lt;!&ndash; 无数据时显示空提示文字 &ndash;&gt;-->
<!--    @selection-change="handleSelectionChange"  &lt;!&ndash; 勾选复选框触发，收集选中文件 &ndash;&gt;-->

<!--    表格数据源绑定了 fileStore.files；Vue 监听到 fileStore.files 变量发生改变，会自动重新执行表格渲染，页面立刻更新展示搜索出来的文件；-->
<!--    搜索场景：不刷新文件夹，直接覆盖数组-->
<!--    搜索不需要加载当前文件夹全部文件，后端直接返回匹配的文件列表，前端直接替换 fileStore.files，页面瞬间变成搜索结果，不调用 refreshCurrentFolder；-->
<!--    清空搜索场景：才会真正刷新文件夹-->
<!--    执行 clearSearch → 调用 refreshCurrentFolder()，重新请求当前文件夹全部原始文件，覆盖掉搜索结果，列表恢复正常目录文件。-->

    <el-table
      v-loading="loading"
      :data="fileStore.files"
      :height="tableHeight"
      class="file-table pan-table"
      tooltip-effect="dark"
      empty-text="当前目录暂无文件"
      @selection-change="handleSelectionChange"
    >
      <!-- 第一列：多选复选框 -->
      <el-table-column type="selection" width="55" />
      <!-- 文件名列，支持排序、文字超长悬浮提示，最小宽度280px -->
      <el-table-column label="文件名" prop="filename" sortable show-overflow-tooltip min-width="280">
        <!-- #default 自定义单元格内容插槽，row代表当前这一行文件完整数据 -->
<!--        插槽里解构出来的 { row } 每一行对应的，就是 fileStore.files 数组里当前循环到的那一个元素。-->
        <template #default="{ row }">
          <div
            class="file-row-container"
            @mouseenter="showOperation($event)"
            @mouseleave="hiddenOperation($event)"
          >
            <!-- 文件图标+文件名区域，点击打开文件夹/预览文件 ，手动把插槽里的 row 作为实参，传进了 clickFilename 函数。-->
            <div class="file-name-content" @click="clickFilename(row)">
              <!-- 动态文件图标：根据文件类型展示不同图标（图片/视频/文档/文件夹） -->
            <!-- <i> 最初是"斜体文字"标签，现在绝大多数场景下是用来放图标的-->
              <i :class="getFileFontElement(row.fileType)" class="file-font-icon" />
              <!-- 搜索高亮文字：搜索模式下匹配文字标红高亮 -->
            <!--span 是一个"无样式"的行内容器，用来标记一小段内容，方便你单独改颜色、加事件、套图标等，不影响整体布局。-->
              <span
                v-if="isSearchMode && row.highlightFilename"
                class="file-name"
                v-html="row.highlightFilename"
              />
              <!-- 普通展示文件名，无搜索时使用 -->
              <span v-else class="file-name">{{ row.filename }}</span>
            </div>

            <!-- 右侧悬浮操作按钮容器，默认透明隐藏，鼠标移入才显示 -->
            <div class="file-operation-content">
              <div class="pan-file-operations">
                <!-- 预览按钮：文件夹不展示，仅文件可见，@click.stop阻止冒泡，避免点按钮触发打开文件 -->
<!--                v-if="!row.folderFlag"	条件判断	只有文件（非文件夹）才显示，文件夹不显示-->
<!--                effect="light"	浅色主题	提示框背景为白色（dark 是黑色）-->
<!--                content="预览"	提示文字	鼠标悬停时显示"预览"-->
<!--                placement="top"	显示位置	提示框出现在按钮上方-->
<!--                icon="View"	图标	显示"眼睛"图标（查看/预览）-->
<!--                type="primary"	主题色	蓝色按钮-->
<!--                size="small"	尺寸	小号按钮-->
<!--                circle	圆形	按钮是圆形（不是方形）-->
<!--                @click.stop="previewFile(row)"	点击事件	.stop 阻止事件冒泡，点击时执行 previewFile 方法-->
                <el-tooltip v-if="!row.folderFlag" effect="light" content="预览" placement="top">
                  <el-button icon="View" type="primary" size="small" circle @click.stop="previewFile(row)" />
                </el-tooltip>
                <!-- 下载按钮：仅文件可用，文件夹不能下载 -->
                <el-tooltip v-if="!row.folderFlag" effect="light" content="下载" placement="top">
                  <el-button icon="Download" type="info" size="small" circle @click.stop="downloadFile(row)" />
                </el-tooltip>
                <!-- AI分析按钮：只有支持AI解析的文件才显示（文档、图片等） -->
                <el-tooltip v-if="supportsAi(row)" effect="light" content="AI 分析" placement="top">
                  <el-button type="primary" plain size="small" circle @click.stop="openAiDrawer(row)">
                    <el-icon><Cpu /></el-icon>
                  </el-button>
                </el-tooltip>
                <!-- 重命名按钮：文件/文件夹都支持 -->
                <el-tooltip effect="light" content="重命名" placement="top">
                  <el-button icon="Edit" type="warning" size="small" circle @click.stop="renameFile(row)" />
                </el-tooltip>
                <!-- 删除按钮 -->
                <el-tooltip effect="light" content="删除" placement="top">
                  <el-button icon="Delete" type="danger" size="small" circle @click.stop="deleteFile(row)" />
                </el-tooltip>
                <!-- 分享按钮 -->
                <el-tooltip effect="light" content="分享" placement="top">
                  <el-button icon="Share" type="success" size="small" circle @click.stop="shareFile(row)" />
                </el-tooltip>
              </div>
            </div>
          </div>
        </template>
      </el-table-column>

      <!-- 文件大小列，居中、可排序、宽度固定120px -->
      <el-table-column prop="fileSizeDesc" sortable label="大小" width="120" align="center" />
      <!-- 修改日期列，格式化时间展示 -->
      <el-table-column prop="updateTime" sortable align="center" label="修改日期" width="180">
        <template #default="{ row }">
          {{ formatDate(row.updateTime) }}
        </template>
      </el-table-column>
    </el-table>
  </section>
    <!-- 分享弹窗：点击分享按钮弹出，v-model控制弹窗显示隐藏 -->
    <!--    :close-on-click-modal="false"  ; 点击弹窗外部空白不关闭，防止误操作 &ndash;&gt;-->
  <el-dialog
    v-model="showShareDialog"
    title="分享文件"
    width="500px"
    :close-on-click-modal="false"
    class="share-dialog pan-dialog"
  >
    <!-- 分享表单，绑定表单数据、校验规则 -->
    <el-form ref="shareFormRef" :model="shareForm" :rules="shareRules" label-width="100px">
      <!-- 分享名称输入框，必填校验 -->
      <el-form-item label="分享名称" prop="shareName">
        <el-input v-model="shareForm.shareName" placeholder="请输入分享名称" />
      </el-form-item>
      <!-- 分享类型单选框：带提取码 / 无提取码 -->
      <el-form-item label="分享类型" prop="shareType">
        <el-radio-group v-model="shareForm.shareType">
          <el-radio label="withCode">需要提取码</el-radio>
          <el-radio label="withoutCode">无需提取码</el-radio>
        </el-radio-group>
      </el-form-item>
      <!-- 分享有效期下拉选择 -->
      <el-form-item label="有效期" prop="validity">
        <el-select v-model="shareForm.validity" placeholder="请选择有效期">
          <el-option label="永久有效" value="permanent" />
          <el-option label="1天" value="1day" />
          <el-option label="7天" value="7days" />
          <el-option label="30天" value="30days" />
        </el-select>
      </el-form-item>
    </el-form>
    <!-- 弹窗底部按钮插槽 -->
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="showShareDialog = false">取消</el-button>
        <el-button type="primary" @click="confirmShare">确认分享</el-button>
      </span>
    </template>
  </el-dialog>
    <!-- AI文件侧边抽屉组件，AI分析文件时弹出 -->
<!--    v-model="showAiDrawer"  &lt;!&ndash; 控制抽屉显示隐藏 &ndash;&gt;-->
<!--    :file="currentAiFile"   &lt;!&ndash; 传递当前要分析的文件对象 &ndash;&gt;-->
<!--    :mode="currentAiMode"   &lt;!&ndash; AI模式（解析文档/看图问答等） &ndash;&gt;-->
<!--    @open-file="continueFileAction"  &lt;!&ndash; 抽屉内部打开文件回调事件 &ndash;&gt;-->
  <AiFileDrawer
    v-model="showAiDrawer"
    :file="currentAiFile"
    :mode="currentAiMode"
    @open-file="continueFileAction"
  />
</div>
</template>

<script setup>
// Vue 核心 API：生命周期、响应式、监听器
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
// 路由能力拆分：useRoute 只读当前地址信息，useRouter 执行跳转/前进后退
// 大项目里刻意拆分读写，职责更清晰，避免误修改路由
import { useRoute, useRouter } from 'vue-router'
// ElementPlus 交互组件：轻量消息提示、确认弹窗/输入弹窗
import { ElMessage, ElMessageBox } from 'element-plus'
// 页面用到的图标组件，按需引入，减小打包体积
import { Cpu, Delete, Download, Edit, Folder, FolderAdd, Search, Share } from '@element-plus/icons-vue'

// 接口层：所有后端请求统一封装在 @/api 目录下
// 页面只调用封装好的方法，不关心请求地址、请求方式、拦截器逻辑
// 大项目分层设计：视图层 → 业务逻辑层 → API层 → 后端，解耦后改接口不用动页面代码
import {
  createFolder as createFolderAPI,
  deleteFiles as deleteFilesAPI,
  downloadFile as downloadFileAPI,
  previewFile as previewFileAPI,
  renameFile as renameFileAPI,
  searchFiles
} from '@/api/file'
import { createShare } from '@/api/share'

// Pinia 全局状态仓库，大项目的「单一数据源」核心
// fileStore：管所有文件数据、面包屑路径、当前父目录ID、文件筛选类型
// userStore：管用户信息、根目录ID（用户登录后才会有值）
import { useFileStore } from '@/stores/file'
import { useUserStore } from '@/stores/user'

// 业务组件：抽离成独立组件，可在多个页面复用
import AiFileDrawer from '@/components/AiFileDrawer.vue'
import UploadButton from '@/components/UploadButton.vue'

// 实例化仓库，整个页面共享同一份全局状态
const fileStore = useFileStore()
const userStore = useUserStore()
// 路由实例：读参数、做跳转
const route = useRoute()
const router = useRouter()

// 支持 AI 解析的文件类型编码，用 Set 存储
// 设计原理：Set 的查找时间复杂度是 O(1)，数组是 O(n)
// 大项目列表数据多、频繁判断时，性能差距很明显；新增类型只需要往集合里加数字
const AI_SUPPORTED_FILE_TYPES = new Set([3, 4, 5, 6, 10, 11, 12])

// ========== 加载状态 ==========
const loading = ref(false)       // 表格整体加载遮罩（切换目录、刷新时触发）
const searchLoading = ref(false) // 搜索按钮专属加载态
// 设计原理：拆分两个加载变量，实现更细粒度的体验
// 比如搜索时只让搜索按钮转圈，表格保留旧数据不黑屏，用户体验更好

// ========== 搜索状态 ==========
const isSearchMode = ref(false)  // 是否处于搜索结果模式
const searchKeyword = ref('')    // 搜索框关键词，双向绑定
// 设计原理：搜索是「临时覆盖」状态，不修改仓库里的目录路径
// 退出搜索能立刻恢复原目录列表，不会污染目录导航的状态栈

// ========== 表格与选中状态 ==========
const selectedFiles = ref([])    // 表格勾选的文件数组，批量操作使用
const tableHeight = ref(calcTableHeight()) // 表格动态高度，窗口变化自动适配

// ========== 分享弹窗状态 ==========
const showShareDialog = ref(false)  // 弹窗显隐
const currentShareFile = ref(null)  // 当前要分享的文件对象
const shareFormRef = ref(null)      // 表单实例，用来触发校验
const shareForm = ref({             // 表单数据双向绑定
  shareName: '',
  shareType: '',
  validity: ''
})

// ========== AI 抽屉状态 ==========
const showAiDrawer = ref(false)   // 侧边抽屉显隐
const currentAiFile = ref(null)   // 当前要解析的文件
const currentAiMode = ref('insight') // AI 模式：解析/问答

// 分享表单校验规则，ElementPlus 原生表单校验
// 设计原理：校验规则抽离成对象，统一管理，修改校验规则不用动模板代码
const shareRules = {
shareName: [
  { required: true, message: '请输入分享名称', trigger: 'blur' },
  { min: 1, max: 50, message: '分享名称长度应在 1 到 50 个字符之间', trigger: 'blur' }
],
shareType: [{ required: true, message: '请选择分享类型', trigger: 'change' }],
validity: [{ required: true, message: '请选择有效期', trigger: 'change' }]
}
// 路由 type 参数 → 后端查询编码 的映射表
// 设计原理：前后端字段解耦，前端用语义化的 image/document，后端用数字编码
// 后端改编码、新增分类，只需要改这个映射，不用动业务逻辑代码
const typeMap = {
all: '-1',
image: '7',
document: '3,4,5,6,10,11,12',
video: '9',
music: '8',
other: '1,2'
}
// TODO 路由驱动数据加载（整个页面的灵魂）
// 监听两个依赖：路由上的 type 筛选参数、用户根目录ID
// 点击侧边栏「图片」 → handleChange 跳转路由 /files?type=image
// → 这里 watch 监听到 type 变化 → 调用仓库加载图片类型文件 → 表格自动渲染新数据 → 侧边栏同步高亮。
// watch(
//     要监听的数据,     // 第1个参数：侦听源
//     回调函数,         // 第2个参数：变化时执行的操作
//     配置选项          // 第3个参数：额外配置
// )
watch(
    //     用数组包裹多个侦听源
//     每个元素都是箭头函数 () => xxx，这叫 getter 函数
// Vue 会自动追踪这些函数返回的响应式数据
// 当 route.query.type 或 userStore.rootFileId 任一发生变化，就会触发回调
//     / 侦听源数组：2个源，顺序是 [type参数, 根目录ID]
    [() => route.query.type, () => userStore.rootFileId],
    // 回调参数数组：2个值，顺序和上面严格对应
//     第 1 个侦听源是 route.query.type → 回调数组第 1 位 newType 就是它的最新值
// 第 2 个侦听源是 userStore.rootFileId → 回调数组第 2 位 rootFileId 就是它的最新值
    async ([newType, rootFileId]) => {
      // 根目录ID还没拿到（用户未登录/登录信息未加载），直接退出
      if (!rootFileId) return

      // 切换分类/目录时，先重置搜索状态，避免搜索结果和新目录混淆
      isSearchMode.value = false
      searchKeyword.value = ''
      loading.value = true

      try {
        // 先判断 newType 有没有值（地址栏有没有带 type 参数）；
        // 有值：去 typeMap 映射表里查对应的后端编码，比如 'image' → '7'；
        // 后面加 || '-1' 是兜底：如果用户手动改地址栏输入了非法值（比如 ?type=abc），typeMap[newType] 会是 undefined，这时候兜底为 '-1'（全部文件），防止接口参数报错；
        // 没值：直接取 typeMap.all（值也是 -1，代表全部类型）。
        // 根据路由 type，从映射表拿到后端需要的文件类型编码
        const fileTypes = newType ? typeMap[newType] || '-1' : typeMap.all
        // 调用仓库的统一加载方法，拉取文件列表，同时更新面包屑
        await fileStore.loadFiles(rootFileId, fileTypes)
        // 切换目录后清空选中，防止带着上一页的选中做批量操作
        selectedFiles.value = []
      } finally {
        // 无论成功失败，都关闭加载遮罩，避免页面卡死
        loading.value = false
      }
    },
    { immediate: true }
    // immediate: 页面一创建就立刻执行一次
    // 作用：刷新页面、外链跳转进来，都能自动加载对应数据，不用等手动点击
)

// 页面挂载完成：给浏览器绑定窗口缩放事件
onMounted(() => {
window.addEventListener('resize', updateTableHeight)
})
// 页面销毁前：必须移除事件监听
onBeforeUnmount(() => {
window.removeEventListener('resize', updateTableHeight)
})
// 计算表格可用高度：视口总高度 - 顶部面包屑+工具栏的固定高度，最小320px
function calcTableHeight() {
return Math.max(window.innerHeight - 330, 320)
}
// 缩放时更新高度
function updateTableHeight() {
tableHeight.value = calcTableHeight()
}
// 表格勾选变化时，同步更新选中数组
function handleSelectionChange(selection) {
selectedFiles.value = Array.isArray(selection) ? selection : []
}
// 鼠标移入文件行：给当前行的操作按钮加 show 类，显示按钮
function showOperation(event) {
const operationContent = event.currentTarget?.querySelector('.file-operation-content')
operationContent?.classList.add('show')
}
// 鼠标移出：移除类，隐藏按钮
function hiddenOperation(event) {
const operationContent = event.currentTarget?.querySelector('.file-operation-content')
operationContent?.classList.remove('show')
}
// 兼容多种字段名，统一取出文件ID
// 设计原理：大项目多模块对接，不同接口返回的ID字段可能不一样（id/fileId/file_id）
// 做一层适配函数，业务代码永远调用 getFileId，不用关心底层字段差异
function getFileId(file) {
return file?.id || file?.fileId || file?.file_id || ''
}
// 文件类型 → 图标类名 映射
function getFileFontElement(fileType) {
//   来自第三方图标库 Font Awesome
const fileTypeMap = {
  0: 'fa fa-folder-o',
  2: 'fa fa-file-archive-o',
  3: 'fa fa-file-excel-o',
  4: 'fa fa-file-word-o',
  5: 'fa fa-file-pdf-o',
  6: 'fa fa-file-text-o',
  7: 'fa fa-file-image-o',
  8: 'fa fa-file-audio-o',
  9: 'fa fa-file-video-o',
  10: 'fa fa-file-powerpoint-o',
  11: 'fa fa-file-code-o',
  12: 'fa fa-file-code-o'
}
return fileTypeMap[fileType] || 'fa fa-file'
}
// 判断文件是否支持AI
function supportsAi(file) {
return !file?.folderFlag && AI_SUPPORTED_FILE_TYPES.has(Number(file?.fileType))
}
// 点击文件名：根据文件类型分发不同行为
function clickFilename(file) {
if (file?.folderFlag) {
  openFolder(file)// 文件夹 → 进入子目录
  return
}
if (supportsAi(file)) {
  openAiDrawer(file, 'insight')// 支持AI → 先打开AI解析
  return
}
continueFileAction(file)// 普通文件 → 预览或下载
}
// 普通文件处理：能预览就预览，不能就下载
function continueFileAction(file) {
if (canInlinePreview(file?.fileType)) {
  previewFile(file)
  return
}
downloadFile(file)
}
// 场景1：点击表格里的文件夹 → 进入子目录
// 用户点击文件夹 → 获取文件夹ID → 清空搜索 → 加载该文件夹下的内容 → 清空已选文件 → 页面刷新显示新文件夹的内容。
async function openFolder(folder) {
const folderId = getFileId(folder)
if (!folderId) {
  ElMessage.error('文件夹 ID 无效')
  return
}
isSearchMode.value = false//退出搜索模式
loading.value = true //显示加载动画
  try { // 调用仓库加载子目录文件，仓库内部会自动更新面包屑路径
  await fileStore.loadFiles(folderId, fileStore.fileTypes)
  selectedFiles.value = []
} catch (error) {
  ElMessage.error(error?.message || '打开文件夹失败')
} finally {
  loading.value = false
}
}
// 场景2：点击面包屑 → 跳转到指定文件夹（面包屑上级目录 / 根目录）
async function navigateToFolder(folderId) {
  // 传空就跳根目录
const targetFolderId = folderId || userStore.rootFileId
if (!targetFolderId) {
  return
}
isSearchMode.value = false
loading.value = true
try {
  // navigateToFolder 必须调用 loadFiles，因为点击面包屑的本质就是"切换到另一个文件夹"，
  // 而 loadFiles 负责从服务器获取该文件夹的内容并更新页面。如果不调用，页面永远显示旧数据，用户就"迷路"了
  await fileStore.loadFiles(targetFolderId, fileStore.fileTypes)
  // selectFile 传空是为了"清空所有选中的文件"，相当于"取消全选"，这在切换文件夹或点击清空按钮时需要用到。
  selectedFiles.value = []
} finally {
  loading.value = false
}
}
// 场景3：增删改文件后 → 刷新当前目录
async function refreshCurrentFolder() {
const targetFolderId = fileStore.parentId || userStore.rootFileId
if (!targetFolderId) {
  return
}
loading.value = true
try {
  await fileStore.loadFiles(targetFolderId, fileStore.fileTypes)
  selectedFiles.value = []
} finally {
  loading.value = false
}
}

// 执行搜索：回车或点击搜索按钮触发
async function handleSearch() {
  // 获取输入框文字，去掉前后空格
  const keyword = searchKeyword.value?.trim()

  // 如果处理完是空（没输内容/只打空格）
  if (!keyword) {
    // 执行清空搜索逻辑，回到正常文件夹列表
    await clearSearch()
    // 直接结束函数，不再发搜索请求
    return
  }

  // 打开加载状态，按钮转圈、表格遮罩
  searchLoading.value = true
  loading.value = true

  try {
    // 调用后端搜索接口，传两个参数：搜索词、当前文件分类
    // fileStore.files = 后端搜索出来的数组

    const response = await searchFiles({
      keyword,
      fileTypes: fileStore.fileTypes
    })
    // 接口业务成功
    if (response?.success) {
      // 表格列表替换成后端返回的搜索结果
      // 兼容后端没返回数组，防止页面报错
      fileStore.files = Array.isArray(response.data) ? response.data : []
      // 清空之前勾选的文件
      selectedFiles.value = []
      // 标记页面现在处于【搜索结果模式】
      isSearchMode.value = true
    }
  } catch (error) {
    // 网络/后端报错，弹出提示
    ElMessage.error(error?.message || '搜索失败')
  } finally {
    // 不管成功失败，都关闭加载动画
    searchLoading.value = false
    loading.value = false
  }
}



// 清空搜索：恢复当前目录的正常文件列表
async function clearSearch() {
  // 如果本来就不在搜索模式、输入框也没文字，不用执行任何操作，直接退出
  if (!isSearchMode.value && !searchKeyword.value) {
    return
  }
  // 清空搜索输入框文字
  searchKeyword.value = ''
  // 取消搜索模式标记，切回正常浏览文件夹状态
  isSearchMode.value = false
  // 重新加载当前文件夹原本的文件，覆盖掉之前的搜索结果
  await refreshCurrentFolder()
}


async function previewFile(file) {
//   file?.folderFlag 的意思是：如果 file 存在（不是 null 或 undefined），
//   就取 file.folderFlag 的值；如果 file 不存在，就直接返回 undefined，不会报错
if (file?.folderFlag) {
  // ElMessage	Element Plus 的全局消息提示对象
  // 当用户试图预览文件夹时，弹出一个黄色的警告提示条，告诉用户"文件夹暂不支持预览"。
  ElMessage.warning('文件夹暂不支持预览')
  return
}
try {
  await previewSingleFile(file)
} catch (error) {
  ElMessage.error(error?.message || '文件预览失败')
}
}

async function downloadFile(file) {
if (file?.folderFlag) {
  ElMessage.warning('文件夹暂不支持下载')
  return
}
try {
  await downloadSingleFile(file)
  ElMessage.success('文件下载已开始')
} catch (error) {
  ElMessage.error(error?.message || '文件下载失败')
}
}

async function renameFile(file) {
try {
  // ElementPlus 弹窗输入框：
  //
  // 弹窗提示文字：请输入新的文件名，弹窗标题：重命名
  // 按钮文案自定义确定 / 取消
  // inputValue：输入框默认填充当前文件原有名称，方便直接修改
  // await 阻塞代码，直到用户点确定 / 取消才往下执行
  // 解构拿到用户输入的新名称，变量命名 newName
  const { value: newName } = await ElMessageBox.prompt('请输入新的文件名', '重命名', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    inputValue: file?.filename || '',
    inputPlaceholder: '请输入文件名',
    // 前端实时输入校验，不通过则无法点击确定，减少无效请求：
    inputValidator: (value) => {
      if (!value) {
        return '文件名不能为空'
      }
      if (value.length > 255) {
        return '文件名不能超过 255 个字符'
      }
      return true
    }
  })

  if (!newName) {
    return
  }

  await renameFileAPI({
    fileId: String(getFileId(file)),
    newFilename: newName
  })
  ElMessage.success('文件重命名成功')
  await refreshCurrentFolder()
} catch (error) {
  if (error !== 'cancel') {
    ElMessage.error(error?.message || '文件重命名失败')
  }
}
}

async function deleteFile(file) {
try {

  await ElMessageBox.confirm(`确定删除 "${file.filename}" 吗？`, '提示', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  })

  await deleteFilesAPI({
    fileIds: [String(getFileId(file))]
  })
  ElMessage.success('文件删除成功')
  await refreshCurrentFolder()
} catch (error) {
  if (error !== 'cancel') {
    ElMessage.error(error?.message || '删除失败')
  }
}
}
// 新建文件夹：输入弹窗 + 非法字符校验
async function createFolder() {
try {
  // 调用 Element‑Plus 的弹窗输入组件：ElMessageBox.prompt，弹出带输入框的弹窗。
  // 第一个参数：弹窗提示文字
  // 第二个参数：弹窗标题
  // confirmButtonText：确定按钮文字
  // cancelButtonText：取消按钮文字
  // inputPlaceholder：输入框占位提示文字
  const { value: folderName } = await ElMessageBox.prompt('请输入文件夹名称', '新建文件夹', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    inputPlaceholder: '请输入文件夹名称',
    inputValidator: (value) => {// 前端提前校验，减少无效请求
      if (!value) {
        return '文件夹名称不能为空'
      }
      if (value.length > 255) {
        return '文件夹名称不能超过 255 个字符'
      }
      // 校验 Windows 禁用的特殊字符，符合网盘业务规
      if (/[<>:"/\\|?*]/.test(value)) {
        return '文件夹名称不能包含以下字符: < > : " / \\ | ? *'
      }
      return true
    }
  })

  if (!folderName) {
    return
  }

  await createFolderAPI({
    parentId: fileStore.parentId,
    folderName
  })
  ElMessage.success('文件夹创建成功')
  await refreshCurrentFolder()// 操作后刷新列表
} catch (error) {
  if (error !== 'cancel') { // 用户点取消不报错，只有真正异常才提示
    ElMessage.error(error?.message || '文件夹创建失败')
  }
}
}
// 重命名、删除逻辑和上面流程完全一致，只是调用接口不同
// 重命名：renameFileAPI；删除：deleteFilesAPI + 二次确认弹窗

// 打开分享弹窗：回填默认文件名，减少用户输入
function shareFile(file) {
if (!getFileId(file)) {
  ElMessage.error('文件 ID 无效')
  return
}
currentShareFile.value = file
shareForm.value = {
  shareName: file?.filename || '',
  shareType: '',
  validity: ''
}
showShareDialog.value = true
}
// 确认分享：表单校验 → 转换参数 → 调用接口 → 跳转分享页
async function confirmShare() {
if (!shareFormRef.value || !currentShareFile.value) {
  return
}

try {
  await shareFormRef.value.validate()// 触发表单校验
  // 前端语义化参数 → 后端编码参数
  // 后端接收的是 int 类型字段，不能直接传字符串，所以前端在请求前把文本类型转换成后端约定的数字枚举。
  const shareType = shareForm.value.shareType === 'withCode' ? 1 : 0
  const validityMap = {
    permanent: '永久有效',
    '1day': '1天',
    '7days': '7天',
    '30days': '30天'
  }

  await createShare({
    shareName: shareForm.value.shareName,
    shareType,
    shareDayType: validityMap[shareForm.value.validity],
    shareFileIds: [String(getFileId(currentShareFile.value))]
  })

  showShareDialog.value = false
  ElMessage.success('分享创建成功，正在跳转到我的分享')
  setTimeout(() => { // 延时跳转，给用户看提示的时间，体验更柔和
    router.push('/shares')
  }, 800)
} catch (error) {
  ElMessage.error(error?.message || '分享失败，请重试')
}
}

function openAiDrawer(file, mode = 'qa') {
if (!supportsAi(file)) {
  ElMessage.warning('当前文件类型暂不支持 AI 解析')
  return
}
currentAiFile.value = file
currentAiMode.value = mode
showAiDrawer.value = true
}
// 批量下载：过滤文件夹，逐个触发下载
async function batchDownload() {
if (!selectedFiles.value.length) {
  ElMessage.warning('请选择要下载的文件')
  return
}
  // 先过滤掉文件夹，文件夹不能下载
const targetFiles = selectedFiles.value.filter((file) => !file.folderFlag)
if (!targetFiles.length) {
  ElMessage.warning('当前选中项都是文件夹，无法下载')
  return
}

let successCount = 0
  // 循环逐个触发浏览器下载
  // 设计原理：浏览器没有原生批量下载API，都是逐个触发下载
for (const file of targetFiles) {
  try {
    await downloadSingleFile(file)
    successCount++
  } catch (error) {
    console.error('[Files.vue] batch download failed', file, error)
  }
}

if (!successCount) {
  ElMessage.error('批量下载失败')
  return
}
ElMessage.success(`批量下载完成，成功 ${successCount}/${targetFiles.length}`)
}
// 批量删除：一次性传所有ID，减少请求次数
/**
 * 批量删除选中文件/文件夹
 */
async function batchDelete() {
  // selectedFiles.value：页面勾选的文件数组
  // 校验：如果数组长度为0，代表没有勾选任何文件
  if (!selectedFiles.value.length) {
    ElMessage.warning('请选择要删除的文件')
    return // 终止函数，不执行删除逻辑
  }

  try {
    // ElementPlus 确认弹窗，二次确认防止误操作
    await ElMessageBox.confirm(
        // 弹窗提示文案：展示勾选数量
        `确定删除选中的 ${selectedFiles.value.length} 个文件吗？`,
        '提示', // 弹窗标题
        {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning' // 警告样式弹窗
        }
    )

    // map遍历选中文件列表，提取每个文件加密ID，转字符串组成数组传给后端
    // 最终得到 ["id1","id2","id3"] 数组，一次性传给后端批量接口。

    //  .map((file) => ...)    JavaScript 数组的 map 方法
    // 遍历数组中的每一个元素，对每个元素执行回调函数
    // 返回一个新数组，长度与原数组相同
    const idList = selectedFiles.value.map((file) => String(getFileId(file)))
    // 调用批量删除接口，一次性传递全部id，只发起一次网络请求（批量接口优势，不用循环逐个删）
    await deleteFilesAPI({
      fileIds: idList
    })

    // 接口无异常，弹出成功提示
    ElMessage.success('文件批量删除成功')
    // 刷新当前目录，重新拉取文件列表，删除的条目消失
    await refreshCurrentFolder()
  } catch (error) {
    // 弹窗点取消会抛出固定字符串 cancel，这种情况不提示错误
    if (error !== 'cancel') {
      // 网络错误、后端业务异常（权限不足、文件不存在）弹出错误信息
      ElMessage.error(error?.message || '文件批量删除失败')
    }
  }
}

// 是否支持浏览器在线预览
function canInlinePreview(fileType) {
return [5, 6, 7, 8, 9, 11, 12].includes(Number(fileType))
}
// 是否是文本类文件（需要自定义预览页）
function isTextPreviewType(fileType) {
return [6, 11, 12].includes(Number(fileType))
}
// 获取文件对应的 MIME 类型，告诉浏览器该怎么渲染
function getPreviewMimeType(fileType) {
const normalized = Number(fileType)
if (normalized === 5) return 'application/pdf'
if ([6, 11, 12].includes(normalized)) return 'text/plain;charset=utf-8'
if (normalized === 7) return 'image/*'
if (normalized === 8) return 'audio/*'
if (normalized === 9) return 'video/*'
return 'application/octet-stream'
}
// 从响应头 content-disposition 中解析真实文件名
// 兼容标准格式和 UTF-8 编码格式，解决中文文件名乱码
function parseFilenameFromDisposition(disposition, fallbackName = 'download') {
if (!disposition) {
  return fallbackName
}
// 先匹配 UTF-8 编码的文件名
const utf8Match = disposition.match(/filename\*\s*=\s*UTF-8''([^;]+)/i)
if (utf8Match?.[1]) {
  try {
    return decodeURIComponent(utf8Match[1])
  } catch (_) {
    return utf8Match[1]
  }
}
// 再匹配普通文件名
const normalMatch = disposition.match(/filename\s*=\s*"?([^";]+)"?/i)
return normalMatch?.[1] || fallbackName
}
// 原生 a 标签触发浏览器下载，兼容所有浏览器
/**
 * 接收文件Blob与文件名，模拟点击a标签触发浏览器本地下载
 * @param {Blob} blob 文件二进制对象
 * @param {string} filename 保存时的文件名称
 */
function saveBlobToLocal(blob, filename) {
  // 1. 根据二进制Blob生成浏览器临时内存URL，只能当前页面使用
  const url = window.URL.createObjectURL(blob)
  // 2. 创建看不见的a标签DOM元素
  const link = document.createElement('a')
  // 给a标签绑定临时文件地址
  link.href = url
  // download属性：指定下载弹窗默认文件名，优先级高于后端响应头
  link.download = filename
  // 将a标签挂载到页面body，不挂载无法触发点击
  document.body.appendChild(link)
  // 代码模拟人工点击链接，浏览器弹出保存文件窗口
  link.click()
  // 点击完成，移除这个临时a标签，清理DOM
  document.body.removeChild(link)
  // 释放临时URL占用的浏览器内存，防止多次下载造成内存堆积泄漏
  window.URL.revokeObjectURL(url)
}

// 解析后端返回的二进制流，区分「正常文件」和「错误JSON」
// 大项目经典坑：后端接口报错时返回JSON，但前端按文件下载，就会下载一个错误内容的json文件
// 这个函数就是提前识别错误，抛出异常给业务层提示
/**
 * 从axios响应中提取Blob文件流，同时拦截后端返回的JSON错误提示
 * @param {Object} response axios接口返回的完整响应对象
 * @param {string} defaultErrorMessage 解析失败时的默认报错文案
 * @returns {Blob} 合法二进制文件Blob对象
 */
async function extractBlobFromResponse(response, defaultErrorMessage) {
  // 兼容两种情况：axios包装后的响应、原生Blob直接返回
  const blob = response?.data instanceof Blob ? response.data : response;

  // 校验是否为Blob类型，不是则直接抛出预览/下载失败
  if (!(blob instanceof Blob)) {
    throw new Error(defaultErrorMessage);
  }

  // 判断Blob实际是JSON（后端异常时会返回json而非文件流）
  // 判断当前拿到的 Blob 不是图片 / 视频 / 文件，而是后端返回的报错 JSON；
  // 判断后端是不是返回了错误信息，如果是，就读出后端给的报错文字，抛出来提示用户；解析 JSON 失败就用默认提示兜底
  if (blob.type?.includes('application/json')) {
    // 将二进制Blob转为文本字符串
    // 把这个装着 JSON 的二进制 Blob，转成普通字符串；
    const text = await blob.text();
    try {
      // 把字符串转成 JS 对象，取出后端写好的错误文字 message；
      const result = JSON.parse(text);
      // 优先使用后端自定义错误信息，无则用默认文案
      throw new Error(result?.message || defaultErrorMessage);
    } catch (error) {
      // JSON解析失败，抛出默认错误
      if (error instanceof SyntaxError) {
        throw new Error(defaultErrorMessage);
      }
      // 其他错误原样抛出
      throw error;
    }
  }
  // 校验通过，返回正常文件二进制Blob供预览/下载
  return blob;
}


// HTML 转义：防止文本文件里的脚本标签执行 XSS 攻击
// 大项目安全规范：所有用户上传的内容，渲染前必须转义
function escapeHtml(rawText = '') {
return rawText
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')
  .replace(/'/g, '&#39;')
}
// 文本编码兼容：先试UTF-8，不行试GBK，解决中文老文件乱码
async function decodeTextBlob(blob) {
const bytes = await blob.arrayBuffer()
try {
  return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
} catch (_) {
  try {
    return new TextDecoder('gb18030').decode(bytes)
  } catch (_) {
    return new TextDecoder().decode(bytes)
  }
}
}
// 自定义文本预览页面：给代码、txt文件生成一个干净的预览页
function renderTextPreview(previewWindow, text, filename) {
  // 对文件名转义特殊字符，防止XSS、HTML标签错乱
const safeTitle = escapeHtml(filename || '文本预览')
  // 对文件正文转义，避免内容里<>&等符号破坏页面结构、防止注入攻击
const safeText = escapeHtml(text)
  // 打开文档写入流，准备往新窗口写入完整HTML页面
previewWindow.document.open()
  // 拼接完整HTML字符串写入窗口，自带样式实现代码/文本友好展示
previewWindow.document.write(`<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>${safeTitle}</title>
<style>
  body { margin: 0; background: #f7f8fa; color: #1f2329; font-family: Consolas, Monaco, Menlo, monospace; }
  .wrap { padding: 16px; }
  pre { margin: 0; white-space: pre-wrap; word-break: break-word; line-height: 1.6; font-size: 14px; }
</style>
</head>
<body>
<div class="wrap"><pre>${safeText}</pre></div>
</body>
</html>`)
  // 关闭文档写入流，页面渲染完成
previewWindow.document.close()
}
// 单文件预览上层封装：对外只需要传file对象，内部处理所有分支
/**
 * 打开新标签页预览单个文件
 * @param {Object} file 当前要预览的文件对象
 */
async function previewSingleFile(file) {
  // 1. 提取文件唯一加密ID
  const fileId = getFileId(file)
  // ID为空直接抛出错误，上层catch捕获提示用户
  if (!fileId) {
    throw new Error('文件 ID 无效')
  }

  // 2. 打开空白新标签页 _blank
  const previewWindow = window.open('', '_blank')
  // 浏览器弹窗拦截判断：window.open返回null代表被拦截
  if (!previewWindow) {
    throw new Error('浏览器拦截了预览窗口，请允许弹窗后重试')
  }

  try {
    // 3. 请求后端预览接口，传入文件加密ID，后端返回文件二进制流（Blob）
    const response = await previewFileAPI({ fileId: String(fileId) })
    // 解析响应，提取二进制Blob对象；解析失败抛出统一提示文案
    const rawBlob = await extractBlobFromResponse(response, '文件预览失败')

    // 分支1：文本类文件（txt、md、代码等）自定义页面渲染，不用浏览器原生
    if (isTextPreviewType(file?.fileType)) {
      // 将二进制Blob转成可读文本（处理中文编码）
      const decodedText = await decodeTextBlob(rawBlob)
      // 往空白新窗口写入自定义HTML，展示文本内容、文件名
      renderTextPreview(previewWindow, decodedText, file?.filename)
      // 文本预览逻辑执行完毕，终止函数，不走下方图片/PDF逻辑
      return
    }

// 分支2：图片/视频/PDF 浏览器原生预览逻辑
// 1. 确定文件标准MIME类型
// 优先用后端流自带的文件类型；为空就根据文件类型码手动匹配对应格式
    const previewType = rawBlob.type || getPreviewMimeType(file?.fileType)
  // 2. 补全Blob的MIME标识，浏览器才能识别怎么渲染
  // 原有Blob自带type就直接复用；没有则包一层新Blob并绑定刚才算出的type
    const previewBlob = rawBlob.type ? rawBlob : new Blob([rawBlob], { type: previewType })
// 3. 把内存里的二进制Blob生成浏览器临时本地URL（仅当前页面可用）
    const previewUrl = window.URL.createObjectURL(previewBlob)
// 4. 空白预览窗口跳转这个临时链接，浏览器自动用自带工具渲染图片/PDF/视频
    previewWindow.location.href = previewUrl
    // 定时器1分钟后释放临时Blob内存，避免内存泄漏
    setTimeout(() => window.URL.revokeObjectURL(previewUrl), 60000)
  } catch (error) {
    // 任何接口/解析/渲染异常：关闭空白预览窗口，再把错误抛出给外层捕获提示
    previewWindow.close()
    throw error
  }
}

// 单文件下载上层封装
async function downloadSingleFile(file) {
const fileId = getFileId(file)
if (!fileId) {
  throw new Error('文件 ID 无效')
}
// 调用后端下载接口，传入加密文件ID
const response = await downloadFileAPI({ fileId: String(fileId) })
// 解析响应二进制Blob；后端返回JSON报错会自动解析并抛出提示
const blob = await extractBlobFromResponse(response, '文件下载失败')
// 兼容两种大小写header，取出下载头 Content-Disposition
//   键：Content-Disposition
//   值：attachment;filename=\"编码文件名\";filename*=UTF-8''编码文件名
const disposition = response?.headers?.['content-disposition'] || response?.headers?.['Content-Disposition']
// 从响应头中解析后端编码后的真实文件名，解析失败则使用前端文件名称兜底
//   按 ; 切割分段；匹配 filename=、filename*= 两段；取出 URL 编码后的文件名，解码还原成中文；
// 如果头部为空、解析失败，就用兜底值 file?.filename || 'download'。
const filename = parseFilenameFromDisposition(disposition, file?.filename || 'download')
// 浏览器下载Blob文件，弹出保存窗口
//   TODO downloadFileAPI 只是拿到文件二进制字节流，浏览器收到二进制不会自动弹出保存框。
//   HTTP 只是单纯返回一堆字节，浏览器分不清是展示还是下载，必须前端自己处理 Blob、模拟点击才会弹出下载窗口。
//   后端只负责把文件数据发给你，真正触发「保存到本地」的逻辑全在前端 saveBlobToLocal。
saveBlobToLocal(blob, filename)
}
// 统一时间格式化，保证全页面时间展示格式一致
function formatDate(value) {
if (!value) {
  return ''
}
const date = new Date(value)
if (Number.isNaN(date.getTime())) {
  return ''
}
return date.toLocaleString('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit'
})
}
</script>

<style scoped>
.files-page {
display: flex;
flex-direction: column;
gap: 18px;
}

.page-top {
display: flex;
flex-direction: column;
gap: 16px;
padding: 16px 20px;
border-radius: 24px;
background: #fff;
border: 1px solid rgba(226, 232, 240, 0.9);
box-shadow: 0 16px 36px rgba(15, 23, 42, 0.06);
}

.breadcrumb-item {
cursor: pointer;
}

.breadcrumb-content {
display: flex;
align-items: center;
gap: 6px;
}

.breadcrumb-icon {
color: #2563eb;
}

.breadcrumb-text {
color: #334155;
font-weight: 500;
}

.file-table-shell {
border-radius: 24px;
background: #fff;
border: 1px solid rgba(226, 232, 240, 0.9);
box-shadow: 0 16px 36px rgba(15, 23, 42, 0.06);
}

.toolbar-shell {
display: flex;
justify-content: space-between;
gap: 16px;
padding-top: 16px;
border-top: 1px solid rgba(226, 232, 240, 0.9);
}

.toolbar-left,
.toolbar-right {
display: flex;
align-items: center;
flex-wrap: wrap;
gap: 12px;
}

.search-input {
width: 340px;
}

.create-folder-btn {
background: linear-gradient(135deg, #22c55e, #16a34a);
border-color: transparent;
color: #fff;
}

.batch-delete-btn {
border-color: transparent;
}

.batch-download-btn {
border-color: transparent;
}

.file-table-shell {
padding: 10px 14px 16px;
}

:deep(.el-table) {
width: 100%;
border-radius: 18px;
}

:deep(.el-table th) {
background: #f8fafc;
color: #0f172a;
font-weight: 700;
}

.file-row-container {
display: flex;
align-items: center;
justify-content: space-between;
gap: 16px;
}

.file-name-content {
display: flex;
align-items: center;
min-width: 0;
cursor: pointer;
}

.file-font-icon {
margin-right: 14px;
font-size: 20px;
color: #3b82f6;
}

.file-name {
overflow: hidden;
text-overflow: ellipsis;
white-space: nowrap;
}

:deep(.search-highlight) {
background: #fef3c7;
color: #92400e;
border-radius: 4px;
padding: 0 2px;
font-weight: 700;
}

.file-operation-content {
opacity: 0;
pointer-events: none;
transition: opacity 0.2s ease;
}

.file-operation-content.show {
opacity: 1;
pointer-events: auto;
}

.pan-file-operations {
display: flex;
flex-wrap: wrap;
gap: 4px;
padding: 4px 8px;
border-radius: 12px;
background: rgba(255, 255, 255, 0.94);
backdrop-filter: blur(12px);
box-shadow: 0 8px 18px rgba(15, 23, 42, 0.12);
}

.dialog-footer {
display: flex;
justify-content: flex-end;
gap: 12px;
}

@media (max-width: 1100px) {
.toolbar-shell {
  flex-direction: column;
  align-items: stretch;
}

.search-input {
  width: 100%;
}
}

@media (max-width: 768px) {
.page-top {
  padding: 18px;
}

.toolbar-right,
.toolbar-left {
  flex-direction: column;
  align-items: stretch;
}

.file-table-shell {
  padding: 10px;
}
}
</style>
