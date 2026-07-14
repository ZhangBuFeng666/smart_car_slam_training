# Jarvis 持续控制任务 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让所有持续功能在启动后保持运行态，并通过任务卡、自然语言和全部停止可靠结束。

**Architecture:** 在 Jarvis API 内集中管理持续功能活动任务，启动线程只在功能实际运行时设置 `RUNNING`，停止入口统一设置取消标志、停止底层任务并同步状态。有界运动逻辑保持不变。

**Tech Stack:** Python 3、FastAPI、Pydantic、pytest、Android Kotlin（现有状态渲染无需修改）

---

### Task 1: 持续功能保持运行

**Files:**
- Modify: `jarvis_agent/src/jarvis_agent/api.py`
- Modify: `jarvis_agent/tests/test_api.py`

- [ ] 为自动跟随启动后保持 `RUNNING` 写失败测试。
- [ ] 修改功能执行线程，启动成功后保持 `RUNNING`，不写入 `COMPLETED`。
- [ ] 验证有界运动仍进入 `COMPLETED`。

### Task 2: 停止入口统一同步

**Files:**
- Modify: `jarvis_agent/src/jarvis_agent/api.py`
- Modify: `jarvis_agent/tests/test_api.py`

- [ ] 为任务卡停止、自然语言停止和全部停止写失败测试。
- [ ] 提取统一停止与活动任务同步函数。
- [ ] 对八种持续功能应用相同规则。

### Task 3: 重复启动与竞争保护

**Files:**
- Modify: `jarvis_agent/src/jarvis_agent/api.py`
- Modify: `jarvis_agent/tests/test_api.py`

- [ ] 为重复启动复用任务和启动期间停止写失败测试。
- [ ] 查找同功能活动任务并复用。
- [ ] 在线程每次状态更新前检查取消标志，保证 `STOPPED` 为最终状态。

### Task 4: 回归验证

**Files:**
- Review: `jarvis_agent/src/jarvis_agent/api.py`
- Review: `jarvis_agent/tests/test_api.py`

- [ ] 运行 Jarvis 全部测试。
- [ ] 运行 Android Jarvis 状态相关测试和 APK 构建。
- [ ] 检查差异与凭据，未经用户要求不提交或推送。
