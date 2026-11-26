"""Full paths to Windows' own programs, so a file planted nearby is never run instead."""
import os

ROOT = os.environ.get("SystemRoot", r"C:\Windows")
_WHERE = {"explorer.exe": "", "rundll32.exe": "System32", "cmd.exe": "System32", "powershell.exe": r"System32\WindowsPowerShell\v1.0"}


def tool(name: str) -> str:
    return os.path.join(ROOT, _WHERE[name], name)
