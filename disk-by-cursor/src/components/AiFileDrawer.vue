<template>
  <!--
    AI 文件抽屉前端主链路：
    1. 父组件 Files.vue 通过 props 传入 modelValue/file/mode。
    2. 抽屉打开时 handleDrawerOpen() 查询 AI 服务能力，并在洞察模式自动加载摘要和标签。
    3. 点击“读取 AI 结果”会并行请求摘要和标签接口。
    4. 点击“重建索引”会请求后端重新解析文件、生成向量并写入 pgvector。
    5. 在问答区域提问时，会请求后端进行“问题向量化 + 文档片段检索 + 模型回答”。
  -->
  <!-- 侧边抽屉容器：AI文件分析主面板 -->
<!--  :model-value="modelValue"        抽屉显示/隐藏的控制变量，由父组件传入（v-model） &ndash;&gt;-->
<!--  :title="currentFileName"         抽屉标题：当前分析的文件名 &ndash;&gt;-->
<!--  size="760px"                     抽屉宽度：760像素;-->
<!--  direction="rtl"                  抽屉滑出方向：从右侧滑出 &ndash;&gt;-->
<!--  :destroy-on-close="false"       关闭时不销毁组件DOM，下次打开更快，保留输入状态 &ndash;&gt;-->
  <el-drawer
    :model-value="modelValue"
    :title="currentFileName"
    size="760px"
    direction="rtl"
    :destroy-on-close="false"
    class="ai-file-drawer"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <!--
      <div class="drawer-hero">
        <div class="drawer-hero__content">
          <div class="drawer-hero__eyebrow">Document Intelligence</div>
          <div class="drawer-hero__title">{{ currentFileName }}</div>
          <div class="drawer-hero__desc">
            上传后系统会异步生成默认摘要和标签。这里优先读取后台结果，必要时也支持手动刷新。
          </div>
        </div>
        <div class="drawer-hero__meta">
          <el-tag effect="dark" type="success">{{ currentFileTypeLabel }}</el-tag>
          <el-tag type="info">{{ currentFileSize }}</el-tag>
          <el-tag :type="isSupportedFile ? 'success' : 'warning'">
            {{ isSupportedFile ? '支持 AI 解析' : '暂不支持当前文件类型' }}
          </el-tag>
        </div>
      </div>
    -->
    <!-- 滚动容器：内容超出高度时显示自定义滚动条，替代原生滚动 -->
    <el-scrollbar class="drawer-scroll">
      <div class="ai-layout">

        <!-- ========== 第一部分：AI服务状态条 ========== -->
        <section class="status-band">
          <!-- 左侧：状态信息展示 -->
          <div class="status-band__left">
            <div class="status-band__title">
              <el-icon><Cpu /></el-icon>
              <span>AI 服务状态</span>
            </div>
            <!-- 能力标签组：展示AI服务商、模型、向量检索状态 -->
            <div class="status-band__chips">
              <el-tag v-if="capability.provider" round>{{ capability.provider }}</el-tag>
              <el-tag v-if="capability.chatModel" round type="success">{{ capability.chatModel }}</el-tag>
              <el-tag v-if="capability.vectorStoreEnabled" round type="warning">向量检索已启用</el-tag>
              <el-tag v-else round type="info">向量检索未启用</el-tag>
            </div>
            <!-- 辅助提示：数据来源 + 文件最近修改时间 -->
            <div class="status-band__hint">
              <span>摘要与标签默认复用落库结果。</span>
              <span v-if="file?.updateTime">最近修改：{{ formatDateTime(file.updateTime) }}</span>
            </div>
          </div>

          <!-- 右侧：操作按钮组 -->
          <div class="status-band__actions">
            <el-button plain :loading="capabilityLoading" @click="loadCapabilities(true)">刷新状态</el-button>
            <!-- 洞察模式下显示：读取AI结果、重建索引按钮 -->
            <el-button v-if="isInsightMode" type="primary" :disabled="!isSupportedFile" :loading="summaryLoading || tagsLoading" @click="loadInsights">
              读取 AI 结果
            </el-button>
            <el-button v-if="isInsightMode" :disabled="!isSupportedFile" :loading="reindexLoading" @click="reindexDocument">
              重建索引
            </el-button>
            <!-- 打开原文件按钮 -->
            <el-button
              v-if="isInsightMode && fileId"
              type="success"
              plain
              @click="openFile"
            >
              {{ continueOpenLabel }}
            </el-button>
          </div>
        </section>
        <!-- 告警提示1：AI能力加载失败时显示 -->
        <el-alert
          v-if="capabilityError"
          type="warning"
          :closable="false"
          show-icon
          :title="capabilityError"
        />
        <!-- 告警提示2：当前文件类型不支持AI解析时显示 -->
        <el-alert
          v-if="!isSupportedFile && fileId"
          type="info"
          :closable="false"
          show-icon
          title="当前文件类型暂未纳入文档 AI 能力范围。建议使用 PDF、Word、Excel、PPT、TXT、CSV 或代码文本文件。"
        />
        <!-- ========== 第二部分：洞察模式（摘要+标签双栏） ========== -->
        <div v-if="isInsightMode" class="content-grid">
          <!-- 左栏：文档摘要面板 -->
          <section class="insight-panel">
            <div class="panel-head">
              <div class="panel-head__title">
                <el-icon><Document /></el-icon>
                <span>文档摘要</span>
              </div>
              <!-- 重置为默认摘要提示词 -->
              <el-button text :disabled="summaryLoading || !fileId" @click="resetSummaryPrompt">
                默认摘要
              </el-button>
            </div>
            <!-- 预设提示词快捷按钮：一键选择常用摘要视角 -->
            <div class="preset-row">
              <button
                v-for="preset in summaryPresets"
                :key="preset.label"
                class="preset-chip"
                type="button"
                @click="applySummaryPreset(preset.prompt)"
              >
                {{ preset.label }}
              </button>
            </div>
            <!-- 自定义提示词输入框：用户可补充自己关心的分析角度 -->
            <el-input
              v-model="summaryPrompt"
              type="textarea"
              :rows="4"
              resize="none"
              maxlength="300"
              show-word-limit
              placeholder="留空表示读取默认摘要；也可以补充你关心的视角，比如“请突出风险与行动项”。"
            />
            <!-- 摘要操作栏：生成按钮 + 模型信息 -->
            <div class="panel-toolbar">
              <el-button
                type="primary"
                :disabled="!isSupportedFile"
                :loading="summaryLoading"
                @click="loadSummary"
              >
                {{ summaryPrompt.trim() ? '按当前提示词生成' : '读取默认摘要' }}
              </el-button>
              <span class="panel-meta" v-if="summaryModel">
                {{ summaryModel }}<span v-if="summaryMocked"> · mock</span>
              </span>
            </div>
            <!-- 摘要结果展示区：多状态分支渲染 -->
            <div class="panel-body">
              <el-skeleton v-if="summaryLoading" animated :rows="6" />
              <el-empty
                v-else-if="!summaryText && !summaryError"
                description="这里会展示文档的核心内容、关键信息和结论。"
              />
              <!-- 错误状态：生成失败提示 -->
              <el-alert
                v-else-if="summaryError"
                type="error"
                :closable="false"
                show-icon
                :title="summaryError"
              />
              <!-- 正常状态：渲染markdown格式的摘要内容 -->
              <div v-else class="summary-content markdown-content" v-html="renderedSummaryHtml"></div>
            </div>
          </section>
          <!-- 右栏：智能标签面板 -->
          <section class="insight-panel">
            <div class="panel-head">
              <div class="panel-head__title">
                <el-icon><CollectionTag /></el-icon>
                <span>智能标签</span>
              </div>
              <!-- 标签数量选择：切换返回标签的个数 -->
              <div class="topk-group">
                <button
                  v-for="count in [5, 8, 12]"
                  :key="count"
                  class="topk-chip"
                  :class="{ 'is-active': tagTopK === count }"
                  type="button"
                  @click="tagTopK = count"
                >
                  {{ count }} 个
                </button>
              </div>
            </div>
            <!-- 标签操作栏 -->
            <div class="panel-toolbar">
              <el-button
                type="primary"
                plain
                :disabled="!isSupportedFile"
                :loading="tagsLoading"
                @click="loadTags"
              >
                读取标签
              </el-button>
              <span class="panel-meta" v-if="tagsModel">
                {{ tagsModel }}<span v-if="tagsMocked"> · mock</span>
              </span>
            </div>
            <!-- 标签结果展示区 -->
            <div class="panel-body">
              <el-skeleton v-if="tagsLoading" animated :rows="4" />
              <el-empty
                v-else-if="!tagList.length && !tagsError"
                description="标签适合做分类、检索和列表卡片展示。"
              />
              <!-- 错误状态 -->
              <el-alert
                v-else-if="tagsError"
                type="error"
                :closable="false"
                show-icon
                :title="tagsError"
              />
              <!-- 正常状态：标签云展示 -->
              <div v-else class="tag-cloud">
                <span v-for="tag in tagList" :key="tag" class="tag-pill">{{ tag }}</span>
              </div>
            </div>
          </section>
        </div>

        <!-- ========== 第三部分：单文件问答模式 ========== -->
        <section v-if="isQaMode" class="qa-panel">
          <div class="panel-head">
            <div class="panel-head__title">
              <el-icon><ChatDotRound /></el-icon>
              <span>单文件问答</span>
            </div>
            <!-- 开关：是否在答案中附带原文引用片段 -->
            <el-switch v-model="includeReferences" active-text="附带引用" />
          </div>
          <!-- 预设问题快捷按钮：一键填充常见问题 -->
          <div class="preset-row">
            <button
              v-for="suggestion in questionSuggestions"
              :key="suggestion"
              class="preset-chip"
              type="button"
              @click="questionText = suggestion"
            >
              {{ suggestion }}
            </button>
          </div>
          <!-- 提问输入区 -->
          <div class="qa-composer">
            <el-input
              v-model="questionText"
              type="textarea"
              resize="none"
              :rows="3"
              maxlength="500"
              show-word-limit
              placeholder="输入你想追问的问题，例如：这份文档的核心结论是什么？"
              @keyup.ctrl.enter="askQuestion"
            />
            <el-button
              type="primary"
              :disabled="!isSupportedFile || !questionText.trim()"
              :loading="qaLoading"
              @click="askQuestion"
            >
              提问
            </el-button>
          </div>
          <!-- 问答历史列表 -->
          <div class="qa-list">
            <!-- 空状态：还没有提问记录 -->
            <el-empty v-if="!qaHistory.length && !qaLoading" description="先提一个问题，答案会结合文档内容返回。" />

            <!-- 单条问答卡片：循环渲染历史对话 -->
            <article v-for="item in qaHistory" :key="item.id" class="qa-card">
              <div class="qa-card__meta">
                <span>{{ item.time }}</span>
                <span v-if="item.model">{{ item.model }}<span v-if="item.mocked"> · mock</span></span>
              </div>
              <div class="qa-card__question">{{ item.question }}</div>
              <!-- 加载中：骨架屏 -->
              <el-skeleton v-if="item.loading" animated :rows="3" />
              <!-- 错误状态 -->
              <el-alert
                v-else-if="item.error"
                type="error"
                :closable="false"
                show-icon
                :title="item.error"
              />
              <!-- 正常回答 -->
              <template v-else>
                <!-- AI回答内容，markdown渲染 -->
                <div class="qa-card__answer markdown-content" v-html="renderMarkdownToHtml(item.answer)"></div>
                <!-- 引用片段：答案对应的原文出处 -->
                <div v-if="item.references?.length" class="reference-list">
                  <div class="reference-list__title">引用片段</div>
                  <div v-for="reference in item.references" :key="reference" class="reference-item">
                    {{ reference }}
                  </div>
                </div>
              </template>
            </article>
          </div>
        </section>
      </div>
    </el-scrollbar>
  </el-drawer>
</template>


<script setup>
// ===================== 1. 依赖导入 =====================
// Vue 组合式 API
// computed：计算属性，从响应式数据派生出新值，自动更新
// ref：定义响应式基础数据
// watch：监听器，监听数据变化执行副作用
import { computed, ref, watch } from 'vue'

// Element Plus 消息提示组件
import { ElMessage } from 'element-plus'

// Element Plus 图标组件
import { ChatDotRound, CollectionTag, Cpu, Document } from '@element-plus/icons-vue'

// 工具函数：Markdown 转 HTML，用于渲染摘要和回答的富文本
import { renderMarkdownToHtml } from '@/utils/markdown'

// AI 相关后端接口
import {
  askSingleFileQuestion,    // 单文件问答
  generateFileTags,         // 生成智能标签
  getAiCapabilities,        // 获取AI服务能力配置
  indexAiFile,              // 重建文档向量索引
  summarizeFile             // 生成文档摘要
} from '@/api/ai'


// ===================== 2. 父子组件通信：Props + Emits =====================
// Vue 是组件化开发，每个组件都是独立封闭的模块，默认情况下，子组件不能直接读取父组件的数据，也不能直接修改父组件的变量。
// 为了让父子组件能正常配合工作，Vue 制定了标准的通信规则：
//     父 → 子传数据：用 props（属性）
//     子 → 父发通知：用 emits（自定义事件）
// 定义父组件传入的属性
// defineProps 用来声明子组件可以接收哪些来自父组件的入参，同时可以指定类型、默认值，相当于子组件对外的「入参接口」。
// 声明之后，子组件内部就可以通过 props.xxx 读取父组件传进来的数据。
const props = defineProps({
  // 父组件写 v-model="showDrawer"
  // 等价于：:modelValue="showDrawer"（父传子开关） + @update:modelValue="showDrawer = $event"（子通知父改开关）
  modelValue: {
    type: Boolean,
    default: false
    // 控制抽屉显示/隐藏，对应父组件的 v-model
  },
  file: {
    type: Object,
    default: null
    // 当前要分析的文件对象，包含文件名、类型、大小、ID等信息
  },
  mode: {
    type: String,
    default: 'insight'
    // 抽屉模式：insight=洞察模式（摘要+标签），qa=问答模式
  }
})

// 定义向父组件抛出的事件
// 声明后会得到一个 emit 函数，子组件调用 emit('事件名', 参数)，就能触发事件，把消息和数据传给父组件。
const emit = defineEmits(['update:modelValue', 'open-file'])
// update:modelValue：同步抽屉显示状态，实现 v-model 双向绑定
// open-file：通知父组件打开原文件


// ===================== 3. 静态常量配置 =====================
// 支持 AI 解析的文件类型集合（用 Set 存储，查找速度 O(1)，比数组快）
const SUPPORTED_FILE_TYPES = new Set([3, 4, 5, 6, 10, 11, 12])
// 支持内联预览的文件类型集合
const INLINE_PREVIEW_FILE_TYPES = new Set([5, 6, 7, 8, 9, 11, 12])

// 文件类型数字 → 中文名称的映射表
const FILE_TYPE_LABELS = {
  1: '文件',
  2: '压缩包',
  3: 'Excel',
  4: 'Word',
  5: 'PDF',
  6: '文本',
  7: '图片',
  8: '音频',
  9: '视频',
  10: 'PPT',
  11: '代码',
  12: 'CSV'
}

// 摘要预设提示词列表：快捷选择不同的摘要视角
const summaryPresets = [
  { label: '默认', prompt: '' },
  { label: '结构化重点', prompt: '请按主题、关键数据、核心结论、建议四部分输出结构化摘要。' },
  { label: '行动项', prompt: '请聚焦文档中的待办事项、负责人线索、时间节点和风险点。' },
  { label: '管理视角', prompt: '请从管理层视角概括背景、收益、风险和下一步建议。' }
]

// 问答预设问题列表：一键填充常见问题
const questionSuggestions = [
  '这份文档主要讲了什么？',
  '请提炼三个最重要的结论。',
  '这份文档有哪些风险、限制或待办事项？'
]


// ===================== 4. 响应式数据定义（按功能模块分组） =====================
// ---------- 4.1 AI 服务能力相关 ----------
const capability = ref({})          // AI能力配置对象（服务商、模型、向量检索开关）
const capabilityLoading = ref(false) // AI能力加载状态
const capabilityError = ref('')      // AI能力加载错误信息

// ---------- 4.2 文档摘要相关 ----------
const summaryPrompt = ref('')        // 自定义摘要提示词
const summaryText = ref('')          // 摘要结果文本
const summaryModel = ref('')         // 生成摘要用的模型名称
const summaryMocked = ref(false)     // 是否是模拟数据（调试用）
const summaryLoading = ref(false)    // 摘要加载状态
const summaryError = ref('')         // 摘要生成错误信息

// ---------- 4.3 智能标签相关 ----------
const tagTopK = ref(5)               // 返回标签的数量
const tagList = ref([])              // 标签结果数组
const tagsModel = ref('')            // 生成标签用的模型
const tagsMocked = ref(false)
const tagsLoading = ref(false)
const tagsError = ref('')

// ---------- 4.4 单文件问答相关 ----------
const questionText = ref('')         // 用户输入的问题
const includeReferences = ref(true)  // 是否附带原文引用片段
const qaLoading = ref(false)         // 问答整体加载状态
const qaHistory = ref([])            // 问答历史记录列表

// ---------- 4.5 其他状态 ----------
const reindexLoading = ref(false)    // 重建索引的加载状态
const autoLoadedFileKey = ref('')    // 已自动加载的文件标识，避免重复加载


// ===================== 5. 计算属性（从现有数据派生出新值，自动响应更新） =====================
// 兼容多种字段名的文件ID（适配不同数据结构）
const fileId = computed(() => props.file?.id || props.file?.fileId || props.file?.file_id || '')

// 最终生效的抽屉模式，默认洞察模式
const drawerMode = computed(() => (props.mode === 'qa' ? 'qa' : 'insight'))
const isQaMode = computed(() => drawerMode.value === 'qa')       // 是否是问答模式
const isInsightMode = computed(() => drawerMode.value === 'insight') // 是否是洞察模式

// 当前文件唯一标识、抽屉唯一标识（切换文件/模式时用来判断是否重置状态）
const currentFileKey = computed(() => (fileId.value ? String(fileId.value) : ''))
const currentDrawerKey = computed(() => `${currentFileKey.value}:${drawerMode.value}`)

// 派生文件基础展示信息
const currentFileName = computed(() => props.file?.filename || '文档智能分析')
const currentFileSize = computed(() => props.file?.fileSizeDesc || '未知大小')
const currentFileTypeLabel = computed(() => FILE_TYPE_LABELS[Number(props.file?.fileType)] || '未知类型')

// 核心判断：当前文件是否支持 AI 解析（有ID、不是文件夹、类型在支持列表里）
const isSupportedFile = computed(() => Boolean(fileId.value) && !props.file?.folderFlag && SUPPORTED_FILE_TYPES.has(Number(props.file?.fileType)))

// 摘要文本转成 HTML 富文本，直接给模板渲染
const renderedSummaryHtml = computed(() => renderMarkdownToHtml(summaryText.value))

// 抽屉描述文案、打开文件按钮文案（根据模式/类型动态变化）
const drawerDescription = computed(() => (
    isQaMode.value
        ? '这里仅保留单文件问答，你可以直接基于当前文件追问。'
        : '这里会先展示文件摘要与标签，再决定是否继续查看文件内容。'
))
const continueOpenLabel = computed(() => (
    INLINE_PREVIEW_FILE_TYPES.has(Number(props.file?.fileType)) ? '预览文件' : '查看文件'
))


// ===================== 6. 监听器：监听变化自动执行逻辑 =====================
// 监听抽屉显示状态：打开时自动执行初始化逻辑
/**
 * 监听父组件传入的抽屉显隐绑定值 modelValue
 * @param visible modelValue 最新值，true=抽屉打开，false=抽屉关闭
 */
watch(
    () => props.modelValue,
    // 父组件控制打开抽屉时，执行抽屉打开初始化逻辑（加载文档、请求接口等）
    (visible) => {
      if (visible) {
        handleDrawerOpen()
      }
    }
)

// 监听当前抽屉唯一标识（文件ID+模式）：切换文件/模式时重置状态并重新加载
/*
 * 监听抽屉唯一标识 currentDrawerKey
 * key 由【文件ID + 操作模式】拼接而成；切换文件 / 切换查看模式时 key 会变化
 * 作用：切换不同文档/切换模式时清空旧数据、重新初始化新文档内容
 */
watch(currentDrawerKey, () => {
  resetDocumentState() // 先清空所有旧数据
  if (props.modelValue) {
    handleDrawerOpen() // 抽屉打开状态下，重新加载新文件的数据
  }
})


// ===================== 7. 核心业务方法 =====================

/**
 * 重置当前文档的所有状态数据
 * 切换文件、关闭抽屉时调用，避免旧数据残留
 */
function resetDocumentState() {
  // 重置摘要状态
  summaryPrompt.value = ''
  summaryText.value = ''
  summaryModel.value = ''
  summaryMocked.value = false
  summaryLoading.value = false
  summaryError.value = ''

  // 重置标签状态
  tagTopK.value = 5
  tagList.value = []
  tagsModel.value = ''
  tagsMocked.value = false
  tagsLoading.value = false
  tagsError.value = ''

  // 重置问答状态
  questionText.value = ''
  includeReferences.value = true
  qaLoading.value = false
  qaHistory.value = []

  // 重置其他状态
  reindexLoading.value = false
  autoLoadedFileKey.value = ''
}

/**
 * 抽屉打开时的统一入口逻辑
 * 加载AI能力，洞察模式下自动加载摘要和标签
 */
async function handleDrawerOpen() {
  if (!fileId.value) return // 没有文件ID直接返回
  // 返回后端组装的 AI 服务全局配置 JSON，缓存在组件内存capability变量中
  void loadCapabilities() // 加载AI服务能力，void 表示不等待异步结果，不阻塞后续逻辑

  // 洞察模式 + 文件支持AI + 没加载过 → 自动加载摘要和标签
  // 加载摘要标签
  if (isInsightMode.value && isSupportedFile.value && autoLoadedFileKey.value !== currentDrawerKey.value) {
    autoLoadedFileKey.value = currentDrawerKey.value // 标记已加载，避免重复请求
    void loadInsights()
  }
}

/**
 * 加载AI服务能力配置
 * @param {Boolean} force - 是否强制刷新，默认有缓存就不重新请求
 */
async function loadCapabilities(force = false) {
  // 如果当前已经在请求接口了，直接终止函数，不做任何操作。
  if (capabilityLoading.value) return // 加载中直接返回，防重复请求
  // 如果不是强制刷新，并且本地已经有缓存的 AI 配置数据，就直接返回，不重新发接口请求。
  if (!force && capability.value?.chatModel) return // 非强制且已有数据，直接复用

  capabilityLoading.value = true
  capabilityError.value = ''
  try {
    // 查看 AI 能力，配置
    const response = await getAiCapabilities()
    capability.value = response?.data || {}
  } catch (error) {
    capabilityError.value = error?.message || 'AI 服务状态读取失败'
  } finally {
    capabilityLoading.value = false
  }
}

/**
 * 并行加载摘要和标签（洞察模式核心方法）
 * 用 Promise.allSettled 实现并行请求，一个失败不影响另一个
 */
// 该函数并行发起摘要、标签两个 AI 接口，使用 allSettled 保证两个请求独立互不干扰，一个失败不会阻断另一个展示，提升页面加载速度与容错性。
async function loadInsights() {
  await Promise.allSettled([loadSummary(), loadTags()])
}

/**
 * 构建通用的文件请求参数
 * 统一参数格式，避免每个方法重复写
 */
function buildFilePayload() {
  return {
    fileId: String(fileId.value),
    filename: props.file?.filename || ''
  }
}

/**
 * 应用预设摘要提示词
 */
function applySummaryPreset(prompt) {
  summaryPrompt.value = prompt
}

/**
 * 重置摘要提示词为默认空
 */
function resetSummaryPrompt() {
  summaryPrompt.value = ''
}

/**
 * 生成/读取文档摘要
 */
async function loadSummary() {
  // 文件不支持AI，直接退出
  if (!isSupportedFile.value) return

  summaryLoading.value = true  // 摘要区域骨架屏、按钮加载
  summaryError.value = ''      // 清空旧错误

  try {
    // 统一文件基础参数 + 自定义提示词
    const response = await summarizeFile({
      ...buildFilePayload(),
      // 用户没输入自定义提示词就不传prompt，后端走默认摘要规则
      prompt: summaryPrompt.value.trim() || undefined
    })
    const data = response?.data || {}
    summaryText.value = data.summary || ''    // 摘要markdown文本
    summaryModel.value = data.model || ''     // 生成摘要所用大模型
    summaryMocked.value = Boolean(data.mocked) // 是否是模拟假数据
  } catch (error) {
    // 请求失败，保存错误信息，清空摘要
    summaryError.value = error?.message || '摘要读取失败'
    summaryText.value = ''
  } finally {
    summaryLoading.value = false // 结束加载
  }
}


/**
 * 生成智能标签
 */
async function loadTags() {
  if (!isSupportedFile.value) return

  tagsLoading.value = true
  tagsError.value = ''
  try {
    const response = await generateFileTags({
      ...buildFilePayload(),
      topK: tagTopK.value // 标签数量
    })
    const data = response?.data || {}
    // 兜底：确保返回的是数组，避免页面报错
    tagList.value = Array.isArray(data.tags) ? data.tags : []
    tagsModel.value = data.model || ''
    tagsMocked.value = Boolean(data.mocked)
  } catch (error) {
    tagsError.value = error?.message || '标签读取失败'
    tagList.value = []
  } finally {
    tagsLoading.value = false
  }
}

/**
 * 重建文档向量索引
 * 用于索引异常、文件更新后，重新生成向量数据
 */
async function reindexDocument() {
  if (!isSupportedFile.value) return

  reindexLoading.value = true
  try {
    await indexAiFile({
      ...buildFilePayload(),
      forceReindex: true // 强制重建
    })
    ElMessage.success('文档索引已重建，正在刷新摘要与标签')
    await loadInsights() // 重建完自动刷新摘要和标签
  } catch (error) {
    ElMessage.error(error?.message || '重建索引失败')
  } finally {
    reindexLoading.value = false
  }
}

/**
 * 提交问题，进行单文件问答
 * 采用「乐观UI更新」：先把问题加入列表显示加载中，再回填结果，体验更流畅
 */
async function askQuestion() {
  const question = questionText.value.trim()
  if (!question || !isSupportedFile.value) return

  qaLoading.value = true
  // 先创建一条待加载的历史记录，插入到列表最前面
  const historyItem = {
    id: `${Date.now()}-${Math.random().toString(16).slice(2)}`, // 唯一ID：时间戳+随机数
    time: formatDateTime(new Date()),
    question,
    answer: '',
    references: [],
    model: '',
    mocked: false,
    loading: true,  // 标记为加载中，模板显示骨架屏
    error: ''
  }
  qaHistory.value = [historyItem, ...qaHistory.value] // 新记录置顶
  questionText.value = '' // 清空输入框

  try {
    const response = await askSingleFileQuestion({
      ...buildFilePayload(),
      question,
      includeReferences: includeReferences.value // 是否返回引用片段
    })
    const data = response?.data || {}
    // 回填结果到同一条记录里
    historyItem.answer = data.answer || ''
    historyItem.references = Array.isArray(data.references) ? data.references : []
    historyItem.model = data.model || ''
    historyItem.mocked = Boolean(data.mocked)
  } catch (error) {
    historyItem.error = error?.message || '问答失败'
  } finally {
    historyItem.loading = false // 关闭加载状态
    qaLoading.value = false
    // 触发响应式更新（因为直接修改对象属性，手动展开数组确保视图更新）
    qaHistory.value = [...qaHistory.value]
  }
}

/**
 * 通知父组件打开原文件，并关闭抽屉
 */
function openFile() {
  if (!props.file) return
  emit('open-file', props.file)
  emit('update:modelValue', false)
}

/**
 * 时间格式化工具函数
 * 格式化为 月-日 时:分 的简洁形式
 */
function formatDateTime(value) {
  if (!value) return ''
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}
</script>

<style scoped>
.drawer-scroll {
  height: 100%;
}

.ai-layout {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding-bottom: 24px;
}

.drawer-hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
  padding: 18px 20px;
  border-radius: 22px;
  background:
    radial-gradient(circle at top left, rgba(43, 120, 228, 0.28), transparent 40%),
    linear-gradient(135deg, #0f172a, #1d4ed8 58%, #38bdf8);
  color: #fff;
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.26);
}

.drawer-hero__content {
  min-width: 0;
}

.drawer-hero__eyebrow {
  margin-bottom: 8px;
  font-size: 12px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  opacity: 0.82;
}

.drawer-hero__title {
  font-size: 24px;
  font-weight: 700;
  line-height: 1.3;
  word-break: break-word;
}

.drawer-hero__desc {
  margin-top: 10px;
  max-width: 520px;
  font-size: 13px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.86);
}

.drawer-hero__meta {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  max-width: 220px;
}

.status-band,
.insight-panel,
.qa-panel {
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 22px;
  background: linear-gradient(180deg, #ffffff, #f8fbff);
  box-shadow: 0 10px 30px rgba(15, 23, 42, 0.06);
}

.status-band {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  padding: 18px 20px;
}

.status-band__title,
.panel-head__title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 16px;
  font-weight: 700;
  color: #0f172a;
}

.status-band__chips,
.status-band__actions,
.preset-row,
.topk-group,
.tag-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.status-band__hint {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 12px;
  font-size: 13px;
  color: #64748b;
}

.status-band__actions {
  align-items: flex-start;
  justify-content: flex-end;
}

.content-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}

.insight-panel,
.qa-panel {
  padding: 18px 20px 20px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.preset-row {
  margin-bottom: 14px;
}

.preset-chip,
.topk-chip {
  border: 0;
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.preset-chip {
  padding: 8px 12px;
  background: #edf4ff;
  color: #1d4ed8;
}

.preset-chip:hover {
  background: #dbeafe;
  transform: translateY(-1px);
}

.topk-chip {
  padding: 6px 12px;
  background: #f1f5f9;
  color: #475569;
}

.topk-chip.is-active,
.topk-chip:hover {
  background: #dbeafe;
  color: #1d4ed8;
}

.panel-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 14px 0 16px;
}

.panel-meta {
  font-size: 12px;
  color: #64748b;
}

.panel-body {
  min-height: 180px;
}

.markdown-content {
  word-break: break-word;
  line-height: 1.8;
  color: #1e293b;
}

.markdown-content p,
.markdown-content ul,
.markdown-content ol,
.markdown-content blockquote,
.markdown-content hr,
.markdown-content h1,
.markdown-content h2,
.markdown-content h3,
.markdown-content h4,
.markdown-content h5,
.markdown-content h6,
.markdown-content .markdown-code-block {
  margin: 0 0 14px;
}

.markdown-content p:last-child,
.markdown-content ul:last-child,
.markdown-content ol:last-child,
.markdown-content blockquote:last-child,
.markdown-content .markdown-code-block:last-child {
  margin-bottom: 0;
}

.markdown-content h1,
.markdown-content h2,
.markdown-content h3,
.markdown-content h4,
.markdown-content h5,
.markdown-content h6 {
  color: #0f172a;
  line-height: 1.35;
}

.markdown-content h1 {
  font-size: 24px;
}

.markdown-content h2 {
  font-size: 21px;
}

.markdown-content h3 {
  font-size: 18px;
}

.markdown-content h4,
.markdown-content h5,
.markdown-content h6 {
  font-size: 16px;
}

.markdown-content ul,
.markdown-content ol {
  padding-left: 20px;
}

.markdown-content li + li {
  margin-top: 6px;
}

.markdown-content a {
  color: #2563eb;
  text-decoration: none;
}

.markdown-content a:hover {
  text-decoration: underline;
}

.markdown-content blockquote {
  padding: 12px 14px;
  border-left: 4px solid #93c5fd;
  border-radius: 10px;
  background: #eff6ff;
  color: #1e3a8a;
}

.markdown-content code {
  padding: 2px 6px;
  border-radius: 6px;
  background: #e2e8f0;
  color: #0f172a;
  font-size: 0.92em;
  font-family: 'Cascadia Code', 'Consolas', monospace;
}

.markdown-content .markdown-code-block {
  overflow: hidden;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 14px;
  background: #0f172a;
}

.markdown-content .markdown-code-block__label {
  padding: 8px 12px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.2);
  color: #94a3b8;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.markdown-content .markdown-code-block pre {
  margin: 0;
  padding: 14px 16px;
  overflow-x: auto;
}

.markdown-content .markdown-code-block pre code {
  padding: 0;
  background: transparent;
  color: #e2e8f0;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre;
}

.markdown-content hr {
  border: 0;
  border-top: 1px solid rgba(148, 163, 184, 0.4);
}

.qa-card__answer {
  white-space: normal;
}

.tag-cloud {
  padding-top: 6px;
}

.tag-pill {
  padding: 8px 14px;
  border-radius: 999px;
  background: linear-gradient(135deg, #eff6ff, #dbeafe);
  color: #1d4ed8;
  font-size: 13px;
  font-weight: 600;
}

.qa-composer {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 12px;
  margin-bottom: 18px;
}

.qa-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.qa-card {
  padding: 16px;
  border-radius: 18px;
  background:
    radial-gradient(circle at top right, rgba(56, 189, 248, 0.12), transparent 36%),
    #f8fafc;
  border: 1px solid rgba(148, 163, 184, 0.18);
}

.qa-card__meta {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  font-size: 12px;
  color: #64748b;
}

.qa-card__question {
  margin-bottom: 12px;
  font-size: 15px;
  font-weight: 700;
  color: #0f172a;
}

.reference-list {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px dashed rgba(148, 163, 184, 0.5);
}

.reference-list__title {
  margin-bottom: 10px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  color: #64748b;
  text-transform: uppercase;
}

.reference-item {
  padding: 10px 12px;
  border-radius: 12px;
  background: #fff;
  color: #334155;
  font-size: 13px;
  line-height: 1.6;
}

@media (max-width: 960px) {
  .content-grid {
    grid-template-columns: 1fr;
  }

  .status-band,
  .drawer-hero,
  .qa-composer {
    grid-template-columns: 1fr;
    flex-direction: column;
  }

  .status-band__actions {
    justify-content: flex-start;
  }

  .drawer-hero__meta {
    justify-content: flex-start;
    max-width: none;
  }

  .qa-composer {
    display: flex;
    flex-direction: column;
  }
}
</style>
