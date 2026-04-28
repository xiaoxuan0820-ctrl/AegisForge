"""执行器模块：通过 ADB 执行动作序列"""

import subprocess
import time
import signal
from typing import Optional

from autodroid_agent.config import (
    ADB_DEVICE,
    MAX_TAPS,
    MAX_EXECUTION_TIME,
    MAX_STEPS,
)
from autodroid_agent.utils.logger import get_logger
from autodroid_agent.utils.screenshot import take_screenshot

logger = get_logger(__name__)

# 常用应用包名映射（可按需扩展）
APP_PACKAGE_MAP = {
    "youtube": "com.google.android.youtube",
    "chrome": "com.android.chrome",
    "settings": "com.android.settings",
    "微信": "com.tencent.mm",
    "wechat": "com.tencent.mm",
    "支付宝": "com.eg.android.AlipayGphone",
    "淘宝": "com.taobao.taobao",
    "抖音": "com.ss.android.ugc.aweme",
    "tiktok": "com.ss.android.ugc.aweme",
    "bilibili": "tv.danmaku.bili",
    "哔哩哔哩": "tv.danmaku.bili",
    "地图": "com.autonavi.minimap",
    "高德": "com.autonavi.minimap",
    "相机": "com.android.camera2",
    "电话": "com.android.dialer",
    "短信": "com.android.messaging",
}


class ExecutionTimeout(Exception):
    """执行超时异常"""
    pass


class TooManyTaps(Exception):
    """连续点击过多异常"""
    pass


class ExecutionResult:
    """执行结果"""

    def __init__(self):
        self.success = False
        self.steps_executed = 0
        self.total_steps = 0
        self.logs: list[str] = []
        self.screenshot_path: str = ""
        self.error: Optional[str] = None

    def to_dict(self) -> dict:
        return {
            "success": self.success,
            "steps_executed": self.steps_executed,
            "total_steps": self.total_steps,
            "logs": self.logs,
            "screenshot_path": self.screenshot_path,
            "error": self.error,
        }


def _adb_cmd(device_id: str = "") -> list[str]:
    """构造 ADB 命令前缀"""
    from autodroid_agent.config import ADB_PATH
    cmd = [ADB_PATH]
    if device_id:
        cmd.extend(["-s", device_id])
    return cmd


def _exec_adb(args: list[str], timeout: int = 10) -> tuple[int, str, str]:
    """执行 ADB 命令"""
    full_cmd = _adb_cmd(ADB_DEVICE) + args
    logger.debug(f"ADB: {' '.join(full_cmd)}")
    result = subprocess.run(
        full_cmd,
        capture_output=True,
        timeout=timeout,
        text=True,
    )
    return result.returncode, result.stdout, result.stderr


def _action_tap(x: int, y: int) -> bool:
    """点击坐标"""
    code, out, err = _exec_adb(["shell", "input", "tap", str(x), str(y)])
    if code != 0:
        logger.error(f"点击失败 ({x},{y}): {err.strip()}")
        return False
    logger.info(f"✅ tap({x}, {y})")
    return True


def _action_swipe(x1: int, y1: int, x2: int, y2: int, duration: int = 300) -> bool:
    """滑动"""
    code, out, err = _exec_adb([
        "shell", "input", "swipe",
        str(x1), str(y1), str(x2), str(y2), str(duration),
    ])
    if code != 0:
        logger.error(f"滑动失败 ({x1},{y1})->({x2},{y2}): {err.strip()}")
        return False
    logger.info(f"✅ swipe({x1},{y1}) -> ({x2},{y2})")
    return True


def _action_open_app(name: str) -> bool:
    """打开应用"""
    package = APP_PACKAGE_MAP.get(name.lower(), name)
    # 尝试用 monkey 启动
    code, out, err = _exec_adb([
        "shell", "monkey", "-p", package,
        "-c", "android.intent.category.LAUNCHER", "1",
    ], timeout=15)
    if code != 0:
        # 备选方案：am start
        code2, out2, err2 = _exec_adb([
            "shell", "am", "start",
            "-n", f"{package}/.MainActivity",
        ], timeout=15)
        if code2 != 0:
            logger.error(f"打开应用失败 {package}: {err2.strip()}")
            return False
    logger.info(f"✅ open_app({name}) -> {package}")
    return True


def _action_input(text: str) -> bool:
    """输入文本（支持中文）"""

    def _try_adb_input(t: str) -> bool:
        """尝试 ADB input text（仅 ASCII）"""
        escaped = t.replace(" ", "%s").replace("'", "\\'")
        code, out, err = _exec_adb(["shell", "input", "text", escaped])
        return code == 0

    def _try_clipboard_paste(t: str) -> bool:
        """通过剪贴板粘贴输入（支持中文）"""
        # 方法1: 使用 content provider 设置剪贴板（Android 10+）
        try:
            import base64
            encoded = base64.b64encode(t.encode("utf-8")).decode("ascii")
            code, _, _ = _exec_adb([
                "shell", "content", "call", "--uri",
                "content://com.android.clipboard.provider",
                "--method", "setPrimaryClip",
                "--arg", f"text/plain,{encoded}",
            ], timeout=5)
            if code == 0:
                # 模拟长按粘贴键
                _exec_adb(["shell", "input", "keyevent", "KEYCODE_PASTE"], timeout=3)
                return True
        except Exception:
            pass

        # 方法2: 使用 am broadcast（部分设备支持）
        try:
            code, _, _ = _exec_adb([
                "shell", "am", "broadcast", "-a",
                "ADB_INPUT_TEXT", "--es", "msg", t,
            ], timeout=5)
            if code == 0:
                return True
        except Exception:
            pass

        # 方法3: 通过 settings 写剪贴板
        try:
            code, _, _ = _exec_adb([
                "shell", "settings", "put", "global",
                "clipboard_text", t,
            ], timeout=5)
            if code == 0:
                _exec_adb(["shell", "input", "keyevent", "KEYCODE_PASTE"], timeout=3)
                return True
        except Exception:
            pass

        return False

    def _try_adb_keyboard(t: str) -> bool:
        """尝试使用 ADBKeyboard（如果已安装）"""
        try:
            code, _, _ = _exec_adb([
                "shell", "am", "broadcast", "-a",
                "ADB_INPUT_B64", "--es", "msg",
                __import__("base64").b64encode(t.encode("utf-8")).decode(),
            ], timeout=5)
            return code == 0
        except Exception:
            return False

    # 尝试顺序：ADBKeyboard → 剪贴板 → ADB input (仅ASCII)
    if _try_adb_keyboard(text):
        logger.info(f"✅ input('{text[:20]}{'...' if len(text) > 20 else ''}') [ADBKeyboard]")
        return True

    if _try_clipboard_paste(text):
        logger.info(f"✅ input('{text[:20]}{'...' if len(text) > 20 else ''}') [Clipboard]")
        return True

    if text.isascii() and _try_adb_input(text):
        logger.info(f"✅ input('{text[:20]}')")
        return True

    # 最终方案：尝试用 sendevent 逐字符输入
    logger.warning("标准输入方法均失败，尝试 sendevent...")
    return _try_sendevent(text)


def _try_sendevent(text: str) -> bool:
    """通过 sendevent 模拟按键（备选方案）"""
    try:
        code, _, _ = _exec_adb(["shell", "input", "keyevent", "KEYCODE_SEARCH"], timeout=3)
        if code != 0:
            return False
        time.sleep(0.5)
        # 通过剪贴板粘贴（最通用方案）
        from autodroid_agent.utils.screenshot import _adb_prefix
        prefix = " ".join(_adb_prefix(ADB_DEVICE))
        cmd = f'{prefix} shell "echo \\"{text}\\" | clip && input keyevent KEYCODE_PASTE"'
        import subprocess
        r = subprocess.run(cmd, shell=True, capture_output=True, timeout=10)
        return r.returncode == 0
    except Exception:
        return False


def _action_wait(seconds: int | float) -> bool:
    """等待"""
    seconds = max(0.5, float(seconds))
    logger.info(f"⏳ wait({seconds}s)")
    time.sleep(seconds)
    return True


def execute_actions(actions: list[dict]) -> ExecutionResult:
    """
    执行动作序列

    参数:
        actions: 动作列表

    返回:
        ExecutionResult 对象
    """
    result = ExecutionResult()
    result.total_steps = len(actions)
    result.steps_executed = 0

    if not actions:
        result.error = "动作序列为空"
        logger.warning("动作序列为空")
        return result

    steps = actions[:MAX_STEPS]
    consecutive_taps = 0
    start_time = time.time()

    # 设置超时信号（仅 Linux/Unix）
    def _timeout_handler(signum, frame):
        raise ExecutionTimeout("执行超时")

    has_signal = hasattr(signal, "SIGALRM")
    if has_signal:
        signal.signal(signal.SIGALRM, _timeout_handler)
        signal.alarm(MAX_EXECUTION_TIME)

    try:
        for i, step in enumerate(steps):
            # 检查总执行时间
            elapsed = time.time() - start_time
            if elapsed > MAX_EXECUTION_TIME:
                raise ExecutionTimeout(f"执行超时（已用时 {elapsed:.1f}s）")

            action = step.get("action", "")
            value = step.get("value")

            logger.info(f"[步骤 {i+1}/{len(steps)}] {action}: {value}")

            ok = False

            if action == "tap":
                if not isinstance(value, (list, tuple)) or len(value) < 2:
                    logger.error(f"tap 参数无效: {value}")
                    result.logs.append(f"步骤 {i+1} 失败: tap 参数无效")
                    continue
                consecutive_taps += 1
                if consecutive_taps > MAX_TAPS:
                    raise TooManyTaps(f"连续点击超过 {MAX_TAPS} 次")
                ok = _action_tap(int(value[0]), int(value[1]))

            elif action == "swipe":
                if not isinstance(value, (list, tuple)) or len(value) < 4:
                    logger.error(f"swipe 参数无效: {value}")
                    result.logs.append(f"步骤 {i+1} 失败: swipe 参数无效")
                    continue
                x1, y1 = int(value[0]), int(value[1])
                x2, y2 = int(value[2]), int(value[3])
                duration = int(value[4]) if len(value) > 4 else 300
                ok = _action_swipe(x1, y1, x2, y2, duration)
                consecutive_taps = 0  # swipe 重置点击计数

            elif action == "open_app":
                ok = _action_open_app(str(value))
                consecutive_taps = 0

            elif action == "input":
                ok = _action_input(str(value))
                consecutive_taps = 0

            elif action == "wait":
                ok = _action_wait(float(value) if value else 1)

            elif action == "screenshot":
                path = take_screenshot(ADB_DEVICE)
                ok = bool(path)
                if ok:
                    result.screenshot_path = path

            else:
                logger.warning(f"未知动作: {action}")
                result.logs.append(f"步骤 {i+1} 跳过: 未知动作 {action}")
                continue

            if ok:
                result.steps_executed += 1
                result.logs.append(f"步骤 {i+1}: {action} ✅")
            else:
                result.logs.append(f"步骤 {i+1}: {action} ❌")

        # 执行结束，截一张最终截图
        final_screenshot = take_screenshot(ADB_DEVICE)
        if final_screenshot:
            result.screenshot_path = final_screenshot

        result.success = True
        logger.info(f"✅ 执行完成: {result.steps_executed}/{result.total_steps} 步成功")

    except TooManyTaps as e:
        result.error = str(e)
        logger.error(f"🛑 安全机制触发: {e}")
    except ExecutionTimeout as e:
        result.error = str(e)
        logger.error(f"🛑 超时终止: {e}")
    except subprocess.TimeoutExpired as e:
        result.error = f"ADB 命令超时: {e}"
        logger.error(result.error)
    except Exception as e:
        result.error = f"执行异常: {e}"
        logger.error(result.error)
    finally:
        if has_signal:
            signal.alarm(0)

    return result
