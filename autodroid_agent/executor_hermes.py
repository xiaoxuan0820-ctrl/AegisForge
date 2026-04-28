"""
Hermes-Agent REST API 执行器
通过 WiFi 调用手机上的 Hermes APK HTTP 接口执行操作
"""

import json
import time
import base64
from typing import Optional
from urllib.request import Request, urlopen
from urllib.error import URLError

from autodroid_agent.config import HERMES_HOST, HERMES_PORT, HERMES_TOKEN
from autodroid_agent.utils.logger import get_logger

logger = get_logger(__name__)


class HermesExecutionError(Exception):
    pass


def _api_url(path: str) -> str:
    return f"http://{HERMES_HOST}:{HERMES_PORT}{path}"


def _api_call(method: str, path: str, data: dict = None, timeout: int = 30) -> dict:
    """调用 Hermes REST API"""
    url = _api_url(path)
    headers = {"Content-Type": "application/json"}
    if HERMES_TOKEN:
        headers["X-APKCLAW-TOKEN"] = HERMES_TOKEN

    body = json.dumps(data).encode() if data else None
    req = Request(url, data=body, headers=headers, method=method)

    try:
        with urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode()
            return json.loads(raw)
    except URLError as e:
        raise HermesExecutionError(f"Hermes API 调用失败 ({url}): {e}")
    except json.JSONDecodeError as e:
        raise HermesExecutionError(f"Hermes 返回非 JSON: {e}")


def check_health() -> bool:
    """检查 Hermes APK 是否在线"""
    try:
        data = _api_call("GET", "/api/agent/status", timeout=5)
        logger.info(f"Hermes 状态: {json.dumps(data, ensure_ascii=False)}")
        return True
    except Exception as e:
        logger.warning(f"Hermes 不可用: {e}")
        return False


def execute_agent_task(task: str) -> dict:
    """
    让 Hermes 内置 Agent 执行自然语言任务（手机端自行规划执行）
    Hermes 自己会调用 DeepSeek 规划多步操作
    """
    logger.info(f"📨 Hermes Agent 执行: {task}")
    try:
        data = _api_call(
            "POST", "/api/agent/execute_task",
            data={"task": task},
            timeout=120,
        )
        return data
    except HermesExecutionError as e:
        return {"success": False, "error": str(e)}


def execute_actions(actions: list[dict]) -> dict:
    """逐条执行动作（通过 Hermes 工具 API）"""
    steps = 0
    total = len(actions)
    logs = []
    screenshot_path = ""

    for i, step in enumerate(actions):
        action = step.get("action", "")
        value = step.get("value")

        logger.info(f"[步骤 {i+1}/{total}] {action}: {value}")

        ok = True
        try:
            if action == "tap":
                if isinstance(value, (list, tuple)) and len(value) >= 2:
                    _api_call("POST", "/api/tool/tap", data={
                        "x": int(value[0]), "y": int(value[1]),
                    }, timeout=10)

            elif action == "swipe":
                if isinstance(value, (list, tuple)) and len(value) >= 4:
                    _api_call("POST", "/api/tool/swipe", data={
                        "x1": int(value[0]), "y1": int(value[1]),
                        "x2": int(value[2]), "y2": int(value[3]),
                    }, timeout=10)

            elif action == "open_app":
                _api_call("POST", "/api/tool/open_app", data={
                    "package_name": str(value),
                }, timeout=15)

            elif action == "input":
                _api_call("POST", "/api/tool/input_text", data={
                    "text": str(value),
                }, timeout=10)

            elif action == "wait":
                time.sleep(max(0.5, float(value) if value else 1))

            elif action == "screenshot":
                resp = _api_call("GET", "/api/tool/screenshot", timeout=15)
                img_b64 = resp.get("data", resp.get("screenshot", ""))
                if img_b64:
                    from datetime import datetime
                    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
                    fname = f"screenshot_hermes_{ts}.png"
                    fpath = __import__("os").path.join(
                        __import__("autodroid_agent.config").SCREENSHOTS_DIR, fname
                    )
                    with open(fpath, "wb") as f:
                        f.write(base64.b64decode(img_b64))
                    screenshot_path = fpath
                    logger.info(f"📸 截图已保存: {fpath}")

            else:
                logger.warning(f"未知动作: {action}")
                logs.append(f"步骤 {i+1}: {action} ⚠️ 跳过")
                continue

            steps += 1
            logs.append(f"步骤 {i+1}: {action} ✅")

        except Exception as e:
            ok = False
            logs.append(f"步骤 {i+1}: {action} ❌ {e}")
            logger.error(f"步骤 {i+1} 失败: {e}")

    return {
        "success": steps == total,
        "steps_executed": steps,
        "total_steps": total,
        "logs": logs,
        "error": None if steps == total else "部分步骤失败",
    }
