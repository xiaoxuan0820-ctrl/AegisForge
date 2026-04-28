"""
AegisPhone 🔱 配置模块
AutoDroid + Hermes 双后端 Android 自动化控制系统
"""

import os
from dotenv import load_dotenv

load_dotenv()

# ==================== LLM 配置 ====================
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
OPENAI_BASE_URL = os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1")
LLM_MODEL = os.getenv("LLM_MODEL", "gpt-4o")

# ==================== 执行后端 ====================
# 可选: "adb" (AutoDroid/ADB), "hermes" (Hermes-Agent REST API)
EXECUTOR_BACKEND = os.getenv("EXECUTOR_BACKEND", "adb")

# ==================== ADB 配置 ====================
ADB_PATH = os.getenv("ADB_PATH", "/opt/homebrew/bin/adb")
ADB_DEVICE = os.getenv("ADB_DEVICE", "")

# ==================== Hermes REST API 配置 ====================
HERMES_HOST = os.getenv("HERMES_HOST", "192.168.1.100")  # 手机 WiFi IP
HERMES_PORT = int(os.getenv("HERMES_PORT", "9527"))
HERMES_TOKEN = os.getenv("HERMES_TOKEN", "")  # Bearer token

# ==================== 飞书配置 ====================
FEISHU_WEBHOOK_PORT = int(os.getenv("FEISHU_WEBHOOK_PORT", "5000"))
FEISHU_VERIFY_TOKEN = os.getenv("FEISHU_VERIFY_TOKEN", "")
FEISHU_BOT_NAME = os.getenv("FEISHU_BOT_NAME", "AegisPhone")

# ==================== 安全机制 ====================
MAX_TAPS = int(os.getenv("MAX_TAPS", "5"))
MAX_EXECUTION_TIME = int(os.getenv("MAX_EXECUTION_TIME", "60"))
MAX_STEPS = int(os.getenv("MAX_STEPS", "30"))

# ==================== 路径 ====================
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOG_DIR = os.path.join(BASE_DIR, "logs")
SCREENSHOTS_DIR = os.path.join(BASE_DIR, "screenshots")
LOG_FILE = os.path.join(LOG_DIR, "run.log")
PROMPT_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "prompt.txt")
