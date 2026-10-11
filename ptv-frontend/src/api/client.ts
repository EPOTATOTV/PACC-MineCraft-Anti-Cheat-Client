import { adminApi } from './admin'
import { playerApi } from './player'
import { accountsApi } from './accounts'
import { detectionApi } from './detection'
import { redscreenApi } from './redscreen'
import { biApi } from './bi'
import { dfApi } from './df'
import { v52Api } from './v52'
import { v54Api } from './v54'
import { p1Api } from './p1'
import { competitionApi } from './competition'
import { supportApi } from './support'

// base 请求封装继续从此处 re-export，保持既有引用面不变。
export { request, playerRequest, timedFetch, LOGIN_TIMEOUT_MS } from './http'

// 按领域拆分后在此聚合，对外形状保持不变。
export const api = {
  ...adminApi,
  ...playerApi,
  ...accountsApi,
  ...detectionApi,
  ...redscreenApi,
  ...biApi,
  ...dfApi,
  ...v52Api,
  ...v54Api,
  ...p1Api,
  ...competitionApi,
  ...supportApi,
}
