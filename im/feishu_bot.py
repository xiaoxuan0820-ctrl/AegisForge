"""飞书（Feishu）Webhook 服务

提供 HTTP 接口接收飞书机器人消息，转发给 AutoDroid Agent 处理并返回结果。

API:
    POST /feishu/webhook   接收飞书消息
    GET  /feishu/health    健康检查
"""

import os
import json
import sys

# 确保项目根目录在 Python 路径中
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from flask import Flask, request, jsonify

from autodroid_agent.router import route_feishu
from autodroid_agent.agent import parse_actions
from autodroid_agent.executor import execute_actions
from autodroid_agent.config import FEISHU_VERIFY_TOKEN, FEISHU_BOT_NAME
from autodroid_agent.utils.logger import get_logger

logger = get_logger(__name__)

app = Flask(__name__)


def _handle_text_message(text: str, sender_id: str) -> dict:
    """处理文本消息（根据 EXECUTOR_BACKEND 自动选择后端）"""
    message = route_feishu(text, sender_id)
    logger.info(f"📨 [飞书] {sender_id}: {text}")

    from autodroid_agent.config import EXECUTOR_BACKEND

    # Hermes 后端：让手机端自行规划执行
    if EXECUTOR_BACKEND == "hermes":
        from autodroid_agent.executor_hermes import execute_agent_task, check_health
        if not check_health():
            return {"success": False, "error": "Hermes APK 不可用，请确认手机已连接"}
        return execute_agent_task(text)

    # ADB 后端：LLM 解析 + 本地逐条执行
    try:
        actions = parse_actions(text)
    except Exception as e:
        logger.error(f"❌ 指令解析失败: {e}")
        return {"success": False, "error": str(e)}

    result = execute_actions(actions)
    return result.to_dict()


def _build_reply(result: dict) -> str:
    """构造飞书回复文本"""
    if result["success"]:
        lines = [
            f"✅ 执行完成！({result['steps_executed']}/{result['total_steps']} 步)",
            "---",
        ]
        for log in result.get("logs", []):
            lines.append(log)
        if result.get("screenshot_path"):
            lines.append(f"\n📸 截图已保存")
    else:
        lines = [
            f"❌ 执行失败",
            f"错误: {result.get('error', '未知错误')}",
        ]
    return "\n".join(lines)


@app.route("/feishu/webhook", methods=["POST"])
def feishu_webhook():
    """飞书消息回调"""
    try:
        data = request.get_json(force=True)
    except Exception:
        return jsonify({"error": "无效的 JSON"}), 400

    if not data:
        return jsonify({"error": "空请求体"}), 400

    logger.debug(f"飞书原始请求: {json.dumps(data, ensure_ascii=False)}")

    # 飞书事件回调格式处理
    # 飞书自定义机器人的回调格式通常为:
    # {
    #   "token": "...",
    #   "challenge": "...",  // 首次验证
    #   "event": {
    #       "sender": {"sender_id": {"user_id": "..."}},
    #       "message": {"content": "..."}
    #   }
    # }

    # 处理 URL 验证挑战
    challenge = data.get("challenge", "")
    if challenge:
        logger.info("🔑 处理飞书 URL 验证挑战")
        return jsonify({"challenge": challenge})

    # Token 验证
    token = data.get("token", "")
    if FEISHU_VERIFY_TOKEN and token != FEISHU_VERIFY_TOKEN:
        logger.warning(f"Token 验证失败: {token}")
        return jsonify({"error": "token 无效"}), 403

    # 解析消息内容
    event = data.get("event", data)
    sender_info = event.get("sender", {})
    sender_id = (
        sender_info.get("sender_id", {}).get("user_id", "")
        or event.get("sender_id", "")
        or "unknown"
    )

    # 尝试从不同格式中提取文本
    message = event.get("message", {})
    text = ""

    if isinstance(message, dict):
        content = message.get("content", "")
        # 飞书的 content 可能是 JSON 字符串
        if isinstance(content, str):
            try:
                content_data = json.loads(content)
                text = content_data.get("text", content)
            except (json.JSONDecodeError, TypeError):
                text = content
        else:
            text = str(content)
    elif isinstance(message, str):
        text = message

    if not text:
        return jsonify({"error": "消息内容为空"}), 400

    # 处理消息
    result = _handle_text_message(text, sender_id)
    reply_text = _build_reply(result)

    # 返回飞书格式的回复
    return jsonify({
        "content": json.dumps({"text": reply_text}, ensure_ascii=False),
        "msg_type": "text",
    })


@app.route("/feishu/health", methods=["GET"])
def health():
    """健康检查"""
    return jsonify({
        "status": "ok",
        "bot_name": FEISHU_BOT_NAME,
    })


if __name__ == "__main__":
    port = int(os.getenv("FEISHU_WEBHOOK_PORT", "5000"))
    logger.info(f"🤖 飞书 Bot 服务启动于端口 {port}")
    app.run(host="0.0.0.0", port=port, debug=False)
