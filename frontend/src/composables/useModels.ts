/**
 * 全局模型清单 composable
 * <p>
 * 数据源:管理面板专用端点 {@code GET /api/models},不是 OpenAI 兼容的 {@code /v1/models}。
 * <p>
 * <strong>为什么不用 /v1/models</strong>:
 * <ul>
 *   <li>{@code /v1/models} 是 OpenAI 兼容契约,接受 {@code Authorization} 头并按 key 白名单过滤</li>
 *   <li>管理面板需要的是后端维护的<strong>全集</strong>(给"支持的模型"多选用),
 *       即便不传 Authorization 头,后端也只会"恰好"返回全集 —— 这是一个隐性的语义耦合</li>
 *   <li>{@code /api/models} 是新加的内部端点,契约解耦,永远返回全集</li>
 * </ul>
 * <p>
 * 设计同 useAccountData:
 * <ul>
 *   <li>结果存内存 ref,组件 watch 即可</li>
 *   <li>in-flight 锁避免多个组件并发拉取</li>
 *   <li>失败 warn 不抛</li>
 *   <li>缓存非空立刻返回;后台异步刷新</li>
 * </ul>
 */
import { ref } from 'vue'

export interface ModelItem {
  id: string
  family?: string
  contextLength?: number
}

const models = ref<ModelItem[]>([])
let loadInFlight: Promise<void> | null = null

async function fetchFromServer(): Promise<void> {
  try {
    // /api/models 永远返回后端全集(不受 Authorization 影响)
    const res = await fetch('/api/models')
    if (!res.ok) throw new Error(`status=${res.status}`)
    const body = (await res.json().catch(() => ({}))) as { data?: ModelItem[] }
    const list = Array.isArray(body.data) ? body.data : []
    models.value = list.map((m) => ({
      id: String(m.id),
      family: m.family,
      contextLength: m.contextLength,
    }))
  } catch (e) {
    console.warn('[models] 拉取模型清单失败:', e)
  }
}

/**
 * 确保模型清单已加载
 * <p>
 * 多个组件并发调用安全(in-flight 锁);后台总会更新 ref
 */
export async function ensureModelsLoaded(): Promise<void> {
  if (models.value.length > 0) {
    void fetchFromServer()
    return
  }
  if (!loadInFlight) {
    loadInFlight = fetchFromServer().finally(() => {
      loadInFlight = null
    })
  }
  await loadInFlight
}

/**
 * 返回响应式 models 列表
 * <p>
 * 不在 setup() 里 await ensureModelsLoaded() —— 让调用方自己决定何时拉,避免阻塞
 * 列表渲染。典型用法:onMounted 里 ensureModelsLoaded(),watch models.value 驱动下拉。
 */
export function useModels() {
  return {
    models,
    ensureModelsLoaded,
  }
}
