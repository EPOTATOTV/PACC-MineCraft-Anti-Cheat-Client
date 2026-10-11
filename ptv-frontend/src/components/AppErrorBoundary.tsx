import { Component, type ErrorInfo, type ReactNode } from 'react'
import { Button, Result } from 'antd'

interface Props {
  children: ReactNode
}

interface State {
  error: Error | null
}

/**
 * 全局错误边界：包住路由，单个页面渲染崩溃时给一个可恢复的降级页，
 * 不再让整站白屏。生产构建会剥掉 console，线上定位依赖各页面自己的上报通道；
 * 后续接入 Sentry 时在 componentDidCatch 里补一句 captureException 即可。
 */
export default class AppErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('页面渲染异常', error, info.componentStack)
  }

  private reset = () => {
    this.setState({ error: null })
  }

  render() {
    if (this.state.error) {
      return (
        <Result
          status="error"
          title="页面出错了"
          subTitle="当前页面渲染异常，已拦截，其余功能不受影响。"
          extra={[
            <Button type="primary" key="retry" onClick={this.reset}>
              重试
            </Button>,
            <Button key="home" onClick={() => { window.location.href = '/' }}>
              返回首页
            </Button>,
          ]}
        />
      )
    }
    return this.props.children
  }
}
