package com.shiro.tyfo.assistant

import android.service.voice.VoiceInteractionService

/**
 * 系统数字助理入口（长按电源键 / 助理手势唤起）。用户在系统「默认数字助理应用」里选中 Tyfo 后，
 * 系统常驻绑定这个服务；真正的界面在 [AssistantSession]。
 */
class AssistantService : VoiceInteractionService()
