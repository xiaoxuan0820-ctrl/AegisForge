package com.apk.claw.android.server

import android.content.Context
import com.apk.claw.android.BuildConfig
import com.apk.claw.android.agent.Persona
import com.apk.claw.android.channel.ChannelManager
import com.apk.claw.android.memory.MemoryManager
import com.apk.claw.android.skill.SkillManager
import com.apk.claw.android.skill.SkillManager.Skill
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD

/**
 * 局域网 HTTP 配置服务器
 * 提供 H5 页面用于在电脑浏览器上配置钉钉/飞书 key + 技能市场
 */
class ConfigServer(
    private val context: Context,
    port: Int = PORT
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "ConfigServer"
        const val PORT = 9527
        private const val MIME_HTML = "text/html"
        private const val MIME_JSON = "application/json"
    }

    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        // CORS 预检请求
        if (session.method == Method.OPTIONS) {
            return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
        }

        val uri = session.uri
        val method = session.method

        return try {
            when {
                (uri == "/" || uri == "/index.html") && method == Method.GET -> serveHtml()
                uri == "/skills.html" && method == Method.GET -> serveSkillsHtml()
                uri == "/api/channels" && method == Method.GET -> handleGetChannels()
                uri == "/api/channels" && method == Method.POST -> handlePostChannels(session)
                uri == "/api/llm" && method == Method.GET -> handleGetLlm()
                uri == "/api/llm" && method == Method.POST -> handlePostLlm(session)
                uri == "/api/skills" && method == Method.GET -> handleGetSkills()
                uri == "/api/skills/install" && method == Method.POST -> handleInstallBuiltinSkill(session)
                uri == "/api/skills/delete" && method == Method.POST -> handleDeleteSkill(session)
                uri == "/api/skills/toggle" && method == Method.POST -> handleToggleSkill(session)
                uri == "/api/persona" && method == Method.GET -> handleGetPersona()
                uri == "/api/persona" && method == Method.POST -> handleSetPersona(session)
                uri == "/api/skills/builtin" && method == Method.GET -> handleGetBuiltinSkills()
                uri == "/api/memory" && method == Method.GET -> handleGetMemories(session)
                uri == "/api/memory" && method == Method.POST -> handleSaveMemory(session)
                uri == "/api/memory/delete" && method == Method.POST -> handleDeleteMemory(session)
                uri == "/debug.html" && method == Method.GET && BuildConfig.DEBUG -> serveDebugHtml()
                uri == "/api/debug/tools" && method == Method.GET && BuildConfig.DEBUG -> handleGetTools()
                uri == "/api/debug/execute" && method == Method.POST && BuildConfig.DEBUG -> handleExecuteTool(session)
                uri == "/api/screenshot" && method == Method.GET -> handleScreenshot()
                uri == "/api/debug/screen-full" && method == Method.GET && BuildConfig.DEBUG -> handleGetScreenFull()
                uri.startsWith("/api/debug/file") && method == Method.GET && BuildConfig.DEBUG -> handleServeFile(session)
                uri == "/api/config/export" && method == Method.GET -> handleConfigExport()
                uri == "/api/config/import" && method == Method.POST -> handleConfigImport(session)
                else -> corsResponse(
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND, MIME_JSON,
                        """{"code":-1,"message":"not found"}"""
                    )
                )
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Server error: ${e.message}")
            corsResponse(
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_JSON,
                    """{"code":-1,"message":"${e.message}"}"""
                )
            )
        }
    }

    private fun serveHtml(): Response {
        val inputStream = context.assets.open("web/index.html")
        val html = inputStream.bufferedReader().use { it.readText() }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_HTML, html))
    }

    // ==================== 技能市场页面 ====================

    private fun serveSkillsHtml(): Response {
        val html = """<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>技能市场</title>
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  background: #f5f5f7;
  color: #1d1d1f;
  min-height: 100vh;
  padding: 24px 16px;
}
.container { max-width: 720px; margin: 0 auto; }
h1 { text-align: center; font-size: 24px; font-weight: 600; margin-bottom: 8px; color: #5856D6; }
.subtitle { text-align: center; font-size: 14px; color: #86868b; margin-bottom: 24px; }
.tabs { display: flex; gap: 8px; margin-bottom: 20px; }
.tab {
  flex: 1; padding: 10px; text-align: center; border-radius: 10px;
  cursor: pointer; font-size: 14px; font-weight: 500;
  background: #e5e5ea; color: #86868b; transition: all 0.2s; border: none;
}
.tab.active { background: #5856D6; color: #fff; }
.section-title { font-size: 15px; font-weight: 600; margin: 16px 0 8px; color: #555; }
.skill-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 12px; }
.skill-card {
  background: #fff; border-radius: 14px; padding: 16px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.08); transition: transform 0.15s;
  position: relative; overflow: hidden;
}
.skill-card:hover { transform: translateY(-2px); }
.skill-icon { font-size: 32px; margin-bottom: 8px; }
.skill-name { font-size: 15px; font-weight: 600; margin-bottom: 4px; }
.skill-desc { font-size: 12px; color: #86868b; line-height: 1.4; margin-bottom: 8px; }
.skill-meta { font-size: 11px; color: #aeaeb2; display: flex; gap: 8px; flex-wrap: wrap; }
.skill-tag {
  background: #f2f2f7; padding: 2px 8px; border-radius: 4px; font-size: 11px;
}
.skill-trigger { font-size: 11px; color: #5856D6; }
.install-btn {
  margin-top: 10px; padding: 6px 14px; border: none; border-radius: 8px;
  font-size: 13px; font-weight: 500; cursor: pointer; transition: all 0.2s;
  background: #5856D6; color: #fff; width: 100%;
}
.install-btn:hover { opacity: 0.85; }
.install-btn.installed { background: #e5e5ea; color: #86868b; cursor: default; }
.install-btn.uninstall { background: #FF3B30; color: #fff; }
.loading { text-align: center; padding: 40px; color: #86868b; }
.toast {
  position: fixed; top: 24px; left: 50%; transform: translateX(-50%) translateY(-80px);
  padding: 12px 24px; border-radius: 10px; font-size: 14px; font-weight: 500;
  color: #fff; z-index: 9999; transition: transform 0.3s ease; pointer-events: none;
}
.toast.show { transform: translateX(-50%) translateY(0); }
.toast.success { background: #34C759; }
.toast.error { background: #FF3B30; }
.empty-state { text-align: center; padding: 40px; color: #86868b; }
</style>
</head>
<body>
<div class="container">
  <h1>🦞 CiCi 技能市场</h1>
  <p class="subtitle">一键安装自动化技能，也可通过对话让 AI 帮你创建</p>

  <div class="tabs">
    <button class="tab active" onclick="switchTab('installed')">已安装</button>
    <button class="tab" onclick="switchTab('builtin')">官方推荐</button>
  </div>

  <div id="installed-section"></div>
  <div id="builtin-section" style="display:none"></div>
</div>
<div class="toast" id="toast"></div>

<script>
const API_BASE = '';
let installedSkills = [];
let builtinSkills = [];

async function loadAll() {
  try {
    const [instRes, builtinRes] = await Promise.all([
      fetch(API_BASE + '/api/skills'),
      fetch(API_BASE + '/api/skills/builtin')
    ]);
    const instJson = await instRes.json();
    const builtinJson = await builtinRes.json();
    if (instJson.code === 0) installedSkills = instJson.data || [];
    if (builtinJson.code === 0) builtinSkills = builtinJson.data || [];
    render();
  } catch (e) {
    document.getElementById('installed-section').innerHTML = '<div class="loading">加载失败: ' + e.message + '</div>';
  }
}

function isInstalled(builtinId) {
  return installedSkills.some(s => s.id === builtinId || s.name === builtinSkills.find(b => b.id === builtinId)?.name);
}

function render() {
  renderInstalled();
  renderBuiltin();
}

function renderInstalled() {
  const el = document.getElementById('installed-section');
  if (!installedSkills.length) {
    el.innerHTML = '<div class="empty-state">暂无已安装的技能<br>去「官方推荐」安装预设技能，或和 AI 说「帮我创建技能」</div>';
    return;
  }
  let html = '<div class="section-title">&#9889; 已安装 ' + installedSkills.length + ' 个技能</div><div class="skill-grid">';
  installedSkills.forEach(s => {
    const trigger = s.triggerType === 'MANUAL' ? '手动触发' : (s.triggerType === 'DAILY' ? '每天 ' + s.triggerTime : '工作日 ' + s.triggerTime);
    html += '<div class="skill-card">'
      + '<div class="skill-icon">' + (s.icon || '🔧') + '</div>'
      + '<div class="skill-name">' + s.name + '</div>'
      + '<div class="skill-desc">' + s.description + '</div>'
      + '<div class="skill-meta">'
      +   '<span class="skill-trigger">' + trigger + '</span>'
      +   '<span class="skill-tag">' + (s.category || 'CUSTOM') + '</span>'
      + '</div>'
      + '<button class="install-btn uninstall" onclick="uninstallSkill(\'' + s.id + '\',\'' + s.name.replace(/'/g,"\\'") + '\')">&#128465; 卸载</button>'
      + '</div>';
  });
  html += '</div>';
  el.innerHTML = html;
}

function renderBuiltin() {
  const el = document.getElementById('builtin-section');
  let html = '<div class="section-title">&#128293; 官方推荐技能</div><div class="skill-grid">';
  builtinSkills.forEach(s => {
    const installed = isInstalled(s.id);
    const trigger = s.triggerType === 'MANUAL' ? '手动' : (s.triggerType === 'DAILY' ? '每天 ' + s.triggerTime : '工作日 ' + s.triggerTime);
    html += '<div class="skill-card">'
      + '<div class="skill-icon">' + (s.icon || '🔧') + '</div>'
      + '<div class="skill-name">' + s.name + '</div>'
      + '<div class="skill-desc">' + s.description + '</div>'
      + '<div class="skill-meta">'
      +   '<span class="skill-trigger">' + trigger + '</span>'
      +   (s.tags ? s.tags.map(t => '<span class="skill-tag">#' + t + '</span>').join('') : '')
      + '</div>'
      + (installed
          ? '<button class="install-btn installed">&#10003; 已安装</button>'
          : '<button class="install-btn" onclick="installSkill(\'' + s.id + '\')">&#43; 安装</button>')
      + '</div>';
  });
  html += '</div>';
  el.innerHTML = html;
}

function switchTab(tab) {
  document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
  event.target.classList.add('active');
  document.getElementById('installed-section').style.display = tab === 'installed' ? 'block' : 'none';
  document.getElementById('builtin-section').style.display = tab === 'builtin' ? 'block' : 'none';
}

async function installSkill(builtinId) {
  try {
    const res = await fetch(API_BASE + '/api/skills/install', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: builtinId })
    });
    const json = await res.json();
    if (json.code === 0) {
      showToast('✅ 已安装: ' + json.data?.name || '', 'success');
      loadAll();
    } else {
      showToast('❌ ' + (json.message || '安装失败'), 'error');
    }
  } catch (e) {
    showToast('❌ ' + e.message, 'error');
  }
}

async function uninstallSkill(id, name) {
  if (!confirm('确定卸载技能「' + name + '」吗？')) return;
  try {
    const res = await fetch(API_BASE + '/api/skills/delete', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: id })
    });
    const json = await res.json();
    if (json.code === 0) {
      showToast('已卸载: ' + name, 'success');
      loadAll();
    } else {
      showToast('❌ ' + (json.message || '卸载失败'), 'error');
    }
  } catch (e) {
    showToast('❌ ' + e.message, 'error');
  }
}

function showToast(msg, type) {
  const el = document.getElementById('toast');
  el.textContent = msg;
  el.className = 'toast ' + type;
  requestAnimationFrame(() => el.classList.add('show'));
  setTimeout(() => el.classList.remove('show'), 2500);
}

loadAll();
</script>
</body>
</html>""".trimIndent()
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_HTML, html))
    }

    // ==================== 渠道配置 ====================

    private fun handleGetChannels(): Response {
        val data = JsonObject().apply {
            addProperty("dingtalkAppKey", KVUtils.getDingtalkAppKey())
            addProperty("dingtalkAppSecret", KVUtils.getDingtalkAppSecret())
            addProperty("feishuAppId", KVUtils.getFeishuAppId())
            addProperty("feishuAppSecret", KVUtils.getFeishuAppSecret())
            addProperty("qqAppId", KVUtils.getQqAppId())
            addProperty("qqAppSecret", KVUtils.getQqAppSecret())
            addProperty("discordBotToken", KVUtils.getDiscordBotToken())
            addProperty("telegramBotToken", KVUtils.getTelegramBotToken())
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handlePostChannels(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return corsResponse(
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"code":-1,"message":"invalid json"}"""
                )
            )
        }

        var reinitDingtalk = false
        var reinitFeishu = false
        var reinitQQ = false
        var reinitDiscord = false
        var reinitTelegram = false

        if (json.has("dingtalkAppKey")) {
            KVUtils.setDingtalkAppKey(json.get("dingtalkAppKey").asString)
            reinitDingtalk = true
        }
        if (json.has("dingtalkAppSecret")) {
            val value = json.get("dingtalkAppSecret").asString
            if (!isMaskedValue(value)) {
                KVUtils.setDingtalkAppSecret(value)
                reinitDingtalk = true
            }
        }
        if (json.has("feishuAppId")) {
            KVUtils.setFeishuAppId(json.get("feishuAppId").asString)
            reinitFeishu = true
        }
        if (json.has("feishuAppSecret")) {
            val value = json.get("feishuAppSecret").asString
            if (!isMaskedValue(value)) {
                KVUtils.setFeishuAppSecret(value)
                reinitFeishu = true
            }
        }
        if (json.has("qqAppId")) {
            KVUtils.setQqAppId(json.get("qqAppId").asString)
            reinitQQ = true
        }
        if (json.has("qqAppSecret")) {
            val value = json.get("qqAppSecret").asString
            if (!isMaskedValue(value)) {
                KVUtils.setQqAppSecret(value)
                reinitQQ = true
            }
        }
        if (json.has("discordBotToken")) {
            val value = json.get("discordBotToken").asString
            if (!isMaskedValue(value)) {
                KVUtils.setDiscordBotToken(value)
                reinitDiscord = true
            }
        }
        if (json.has("telegramBotToken")) {
            val value = json.get("telegramBotToken").asString
            if (!isMaskedValue(value)) {
                KVUtils.setTelegramBotToken(value)
                reinitTelegram = true
            }
        }

        if (reinitDingtalk) ChannelManager.reinitDingTalkFromStorage()
        if (reinitFeishu) ChannelManager.reinitFeiShuFromStorage()
        if (reinitQQ) ChannelManager.reinitQQFromStorage()
        if (reinitDiscord) ChannelManager.reinitDiscordFromStorage()
        if (reinitTelegram) ChannelManager.reinitTelegramFromStorage()

        if (reinitDingtalk || reinitFeishu || reinitQQ || reinitDiscord || reinitTelegram) {
            ConfigServerManager.notifyConfigChanged()
        }

        return corsResponse(
            newFixedLengthResponse(Response.Status.OK, MIME_JSON, """{"code":0,"message":"ok"}""")
        )
    }

    private fun handleGetLlm(): Response {
        val data = JsonObject().apply {
            addProperty("llmApiKey", KVUtils.getLlmApiKey())
            addProperty("llmBaseUrl", KVUtils.getLlmBaseUrl())
            addProperty("llmModelName", KVUtils.getLlmModelName())
        }
        return corsResponse(
            newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                JsonObject().apply {
                    addProperty("code", 0)
                    add("data", data)
                    addProperty("message", "ok")
                }.toString()
            )
        )
    }

    private fun handlePostLlm(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return corsResponse(
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"code":-1,"message":"invalid json"}"""
                )
            )
        }

        if (json.has("llmApiKey")) {
            val value = json.get("llmApiKey").asString
            if (!isMaskedValue(value)) {
                KVUtils.setLlmApiKey(value)
            }
        }
        if (json.has("llmBaseUrl")) {
            KVUtils.setLlmBaseUrl(json.get("llmBaseUrl").asString)
        }
        if (json.has("llmModelName")) {
            KVUtils.setLlmModelName(json.get("llmModelName").asString.trim())
        }

        ConfigServerManager.notifyConfigChanged()

        return corsResponse(
            newFixedLengthResponse(Response.Status.OK, MIME_JSON, """{"code":0,"message":"ok"}""")
        )
    }

    // ==================== 技能市场 API ====================

    private fun handleGetSkills(): Response {
        val skills = SkillManager.getAll()
        val arr = JsonArray()
        for (s in skills) {
            arr.add(skillToJson(s))
        }
        return corsResponse(
            newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                JsonObject().apply {
                    addProperty("code", 0)
                    add("data", arr)
                }.toString()
            )
        )
    }

    private fun handleGetBuiltinSkills(): Response {
        val builtins = SkillManager.getBuiltinSkills()
        val arr = JsonArray()
        for (s in builtins) {
            arr.add(skillToJson(s))
        }
        return corsResponse(
            newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                JsonObject().apply {
                    addProperty("code", 0)
                    add("data", arr)
                }.toString()
            )
        )
    }

    private fun handleInstallBuiltinSkill(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return corsResponse(
                newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"code":-1,"message":"invalid json"}"""
                )
            )
        }

        val builtinId = json.get("id")?.asString ?: return corsResponse(
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"missing id"}"""
            )
        )

        val builtin = SkillManager.getBuiltinSkills().find { it.id == builtinId }
            ?: return corsResponse(
                newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                    """{"code":-1,"message":"builtin skill not found"}"""
                )
            )

        // Check if already installed
        val existing = SkillManager.getAll().find { it.name == builtin.name }
        if (existing != null) {
            return corsResponse(
                newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                    """{"code":0,"message":"already installed","data":${skillToJson(existing)}}"""
                )
            )
        }

        val skill = SkillManager.create(builtin)
        return corsResponse(
            newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                JsonObject().apply {
                    addProperty("code", 0)
                    addProperty("message", "installed")
                    add("data", skillToJson(skill))
                }.toString()
            )
        )
    }

    private fun handleDeleteSkill(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return corsResponse(
                newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"code":-1,"message":"invalid json"}"""
                )
            )
        }

        val id = json.get("id")?.asString ?: return corsResponse(
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"missing id"}"""
            )
        )

        val ok = SkillManager.delete(id)
        return corsResponse(
            newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                """{"code":${if (ok) 0 else -1},"message":${if (ok) "\"deleted\"" else "\"not found\""}}"""
            )
        )
    }

    private fun handleToggleSkill(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return corsResponse(
                newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"code":-1,"message":"invalid json"}"""
                )
            )
        }

        val id = json.get("id")?.asString ?: return corsResponse(
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"missing id"}"""
            )
        )
        val enabled = json.get("enabled")?.asBoolean ?: true

        val ok = SkillManager.toggle(id, enabled)
        return corsResponse(
            newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                """{"code":${if (ok) 0 else -1},"message":${if (ok) "\"toggled\"" else "\"not found\""}}"""
            )
        )
    }

    // ==================== 人格 API ====================

    private fun handleGetPersona(): Response {
        val active = Persona.getActive()
        val data = JsonObject().apply {
            addProperty("id", active.id)
            addProperty("name", active.displayName)
            addProperty("description", active.description)
            addProperty("icon", active.icon)
            val list = JsonArray()
            Persona.values().forEach { p ->
                list.add(JsonObject().apply {
                    addProperty("id", p.id)
                    addProperty("name", p.displayName)
                    addProperty("description", p.description)
                    addProperty("icon", p.icon)
                    addProperty("active", p == active)
                })
            }
            add("list", list)
        }
        return corsResponse(
            newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                JsonObject().apply {
                    addProperty("code", 0)
                    add("data", data)
                }.toString()
            )
        )
    }

    private fun handleSetPersona(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return corsResponse(
                newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"code":-1,"message":"invalid json"}"""
                )
            )
        }

        val id = json.get("id")?.asString ?: return corsResponse(
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"missing id"}"""
            )
        )

        val persona = Persona.getById(id)
            ?: return corsResponse(
                newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                    """{"code":-1,"message":"unknown persona: $id"}"""
                )
            )

        Persona.setActive(persona)
        return corsResponse(
            newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                """{"code":0,"message":"switched to ${persona.displayName}"}"""
            )
        )
    }

    // ==================== 工具方法 ====================

    private fun skillToJson(s: Skill): JsonObject {
        return JsonObject().apply {
            addProperty("id", s.id)
            addProperty("name", s.name)
            addProperty("description", s.description)
            addProperty("icon", s.icon)
            addProperty("category", s.category.name)
            addProperty("categoryLabel", s.category.label)
            addProperty("triggerType", s.triggerType.name)
            addProperty("triggerTime", s.triggerTime)
            addProperty("workdaysOnly", s.workdaysOnly)
            addProperty("enabled", s.enabled)
            val tags = JsonArray()
            s.tags.forEach { tags.add(it) }
            add("tags", tags)
            val steps = JsonArray()
            s.steps.forEach { step ->
                steps.add(JsonObject().apply {
                    addProperty("description", step.description)
                })
            }
            add("steps", steps)
        }
    }

    // ==================== 记忆 API ====================

    private fun handleGetMemories(session: IHTTPSession): Response {
        val query = session.parms["query"]?.takeIf { it.isNotBlank() }
        val category = session.parms["category"]
        val memories = when {
            query != null -> MemoryManager.search(query)
            category != null -> {
                val cat = MemoryManager.Category.entries.find {
                    it.key == category || it.name.equals(category, ignoreCase = true)
                }
                if (cat != null) MemoryManager.getAll().filter { it.category == cat }
                else MemoryManager.getAll()
            }
            else -> MemoryManager.getAll()
        }
        val arr = JsonArray()
        for (m in memories) {
            arr.add(JsonObject().apply {
                addProperty("id", m.id)
                addProperty("category", m.category.name)
                addProperty("categoryLabel", m.category.label)
                addProperty("key", m.key)
                addProperty("value", m.value)
                addProperty("appName", m.appName)
                val tags = JsonArray()
                m.tags.forEach { tags.add(it) }
                add("tags", tags)
                addProperty("createdAt", m.createdAt)
                addProperty("updatedAt", m.updatedAt)
            })
        }
        return corsResponse(
            newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                JsonObject().apply {
                    addProperty("code", 0)
                    add("data", arr)
                    addProperty("total", memories.size)
                }.toString()
            )
        )
    }

    private fun handleSaveMemory(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""
        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return corsResponse(
                newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"code":-1,"message":"invalid json"}""")
            )
        }
        val key = json.get("key")?.asString ?: ""
        val value = json.get("value")?.asString ?: ""
        val categoryStr = json.get("category")?.asString ?: "FACT"
        val category = MemoryManager.Category.entries.find {
            it.key == categoryStr || it.name.equals(categoryStr, ignoreCase = true)
        } ?: MemoryManager.Category.FACT
        val appName = json.get("appName")?.asString
        val tags = json.getAsJsonArray("tags")?.map { it.asString } ?: emptyList()
        val item = MemoryManager.save(category, key, value, appName, tags)
        return corsResponse(
            newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                JsonObject().apply {
                    addProperty("code", 0)
                    addProperty("message", "saved")
                    add("data", JsonObject().apply {
                        addProperty("id", item.id)
                        addProperty("category", item.category.name)
                        addProperty("key", item.key)
                        addProperty("value", item.value)
                    })
                }.toString()
            )
        )
    }

    private fun handleDeleteMemory(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""
        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return corsResponse(
                newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"code":-1,"message":"invalid json"}""")
            )
        }
        val id = json.get("id")?.asString ?: return corsResponse(
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"missing id"}""")
        )
        val ok = MemoryManager.delete(id)
        return corsResponse(
            newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                """{"code":${if (ok) 0 else -1},"message":${if (ok) "\"deleted\"" else "\"not found\""}}""")
        )
    }

    // ==================== Debug (仅 DEBUG 构建) ====================

    private fun handleGetScreenFull(): Response {
        val service = com.apk.claw.android.service.ClawAccessibilityService.getInstance()
            ?: return corsResponse(
                newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                    """{"code":-1,"message":"Accessibility service is not running"}"""
                )
            )
        val tree = service.screenTreeFull
        return corsResponse(
            newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                JsonObject().apply {
                    addProperty("code", 0)
                    add("data", JsonObject().apply {
                        addProperty("success", tree != null)
                        addProperty("data", tree ?: "")
                    })
                }.toString()
            )
        )
    }

    private fun serveDebugHtml(): Response {
        val inputStream = context.assets.open("web/debug.html")
        val html = inputStream.bufferedReader().use { it.readText() }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_HTML, html))
    }

    private fun handleGetTools(): Response {
        val tools = ToolRegistry.getAllTools()
        val arr = JsonArray()
        for (tool in tools) {
            val obj = JsonObject().apply {
                addProperty("name", tool.getName())
                addProperty("displayName", tool.getDisplayName())
                addProperty("description", tool.getDescription())
                val params = JsonArray()
                for (p in tool.getParameters()) {
                    params.add(JsonObject().apply {
                        addProperty("name", p.name)
                        addProperty("type", p.type)
                        addProperty("description", p.description)
                        addProperty("required", p.isRequired)
                    })
                }
                add("parameters", params)
            }
            arr.add(obj)
        }
        return corsResponse(
            newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                JsonObject().apply {
                    addProperty("code", 0)
                    add("data", arr)
                }.toString()
            )
        )
    }

    private fun handleExecuteTool(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return corsResponse(
                newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"code":-1,"message":"invalid json"}"""
                )
            )
        }

        val toolName = json.get("tool")?.asString ?: return corsResponse(
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"missing tool name"}"""
            )
        )

        val params = mutableMapOf<String, Any>()
        try {
            json.getAsJsonObject("params")?.entrySet()?.forEach { (key, value) ->
                when {
                    value.isJsonNull -> {}
                    !value.isJsonPrimitive -> params[key] = value.toString()
                    value.asJsonPrimitive.isNumber -> params[key] = value.asNumber
                    value.asJsonPrimitive.isBoolean -> params[key] = value.asBoolean
                    else -> params[key] = value.asString
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Debug param parse error: ${e.message}")
        }

        val toolResult = try {
            ToolRegistry.executeTool(toolName, params)
        } catch (e: Exception) {
            ToolResult.error("Exception: ${e.message}")
        }

        return corsResponse(
            newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                JsonObject().apply {
                    addProperty("code", 0)
                    add("data", JsonObject().apply {
                        addProperty("success", toolResult.isSuccess)
                        addProperty("data", toolResult.data)
                        addProperty("error", toolResult.error)
                    })
                }.toString()
            )
        )
    }

    private fun handleServeFile(session: IHTTPSession): Response {
        val path = session.parms["path"] ?: return corsResponse(
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"missing path param"}"""
            )
        )
        val cacheDir = context.cacheDir.absolutePath
        val file = java.io.File(path)
        if (!file.exists() || !file.absolutePath.startsWith(cacheDir)) {
            return corsResponse(
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_JSON,
                    """{"code":-1,"message":"file not found or access denied"}"""
                )
            )
        }
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, mime, file.inputStream(), file.length()))
    }

    // ==================== 截图预览 API ====================

    private fun handleScreenshot(): Response {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "screencap -p"))
            val pngBytes = process.inputStream.readBytes()
            process.waitFor()
            if (pngBytes.isEmpty()) {
                return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                    """{"code":-1,"message":"screencap returned empty"}"""))
            }
            corsResponse(newFixedLengthResponse(Response.Status.OK, "image/png",
                java.io.ByteArrayInputStream(pngBytes), pngBytes.size.toLong()))
        } catch (e: Exception) {
            corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                """{"code":-1,"message":"${e.message}"}"""))
        }
    }

    // ==================== 配置备份/恢复 API ====================

    private fun handleConfigExport(): Response {
        val data = JsonObject()
        // LLM 配置
        data.addProperty("llmApiKey", KVUtils.getLlmApiKey())
        data.addProperty("llmBaseUrl", KVUtils.getLlmBaseUrl())
        data.addProperty("llmModelName", KVUtils.getLlmModelName())
        // 渠道配置
        data.addProperty("dingtalkAppKey", KVUtils.getDingtalkAppKey())
        data.addProperty("dingtalkAppSecret", KVUtils.getDingtalkAppSecret())
        data.addProperty("feishuAppId", KVUtils.getFeishuAppId())
        data.addProperty("feishuAppSecret", KVUtils.getFeishuAppSecret())
        data.addProperty("qqAppId", KVUtils.getQqAppId())
        data.addProperty("qqAppSecret", KVUtils.getQqAppSecret())
        data.addProperty("discordBotToken", KVUtils.getDiscordBotToken())
        data.addProperty("telegramBotToken", KVUtils.getTelegramBotToken())
        // 当前人格
        data.addProperty("activePersonaId", Persona.getActive().id)
        data.addProperty("_version", 1)
        data.addProperty("_exportedAt", System.currentTimeMillis())
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handleConfigImport(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""
        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return corsResponse(newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"invalid json"}"""))
        }
        val data = json.getAsJsonObject("data") ?: json
        var count = 0
        // LLM 配置
        if (data.has("llmApiKey")) { KVUtils.setLlmApiKey(data.get("llmApiKey").asString); count++ }
        if (data.has("llmBaseUrl")) { KVUtils.setLlmBaseUrl(data.get("llmBaseUrl").asString); count++ }
        if (data.has("llmModelName")) { KVUtils.setLlmModelName(data.get("llmModelName").asString); count++ }
        // 渠道配置
        if (data.has("dingtalkAppKey")) { KVUtils.setDingtalkAppKey(data.get("dingtalkAppKey").asString); count++ }
        if (data.has("dingtalkAppSecret")) { KVUtils.setDingtalkAppSecret(data.get("dingtalkAppSecret").asString); count++ }
        if (data.has("feishuAppId")) { KVUtils.setFeishuAppId(data.get("feishuAppId").asString); count++ }
        if (data.has("feishuAppSecret")) { KVUtils.setFeishuAppSecret(data.get("feishuAppSecret").asString); count++ }
        if (data.has("qqAppId")) { KVUtils.setQqAppId(data.get("qqAppId").asString); count++ }
        if (data.has("qqAppSecret")) { KVUtils.setQqAppSecret(data.get("qqAppSecret").asString); count++ }
        if (data.has("discordBotToken")) { KVUtils.setDiscordBotToken(data.get("discordBotToken").asString); count++ }
        if (data.has("telegramBotToken")) { KVUtils.setTelegramBotToken(data.get("telegramBotToken").asString); count++ }
        // 人格
        if (data.has("activePersonaId")) {
            val pid = data.get("activePersonaId").asString
            Persona.getById(pid)?.let { Persona.setActive(it); count++ }
        }
        ConfigServerManager.notifyConfigChanged()
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON,
            """{"code":0,"message":"配置已恢复，共 $count 项"}"""))
    }

    private fun maskSecret(secret: String): String {
        if (secret.isEmpty()) return ""
        if (secret.length <= 4) return secret
        return "*".repeat(secret.length - 4) + secret.takeLast(4)
    }

    private fun isMaskedValue(value: String): Boolean {
        return value.contains("*")
    }

    private fun corsResponse(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return response
    }
}
