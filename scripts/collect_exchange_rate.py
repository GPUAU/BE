"""Run the one-off collector with local .env values; never print secrets."""
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
names = ('KOREAEXIM_EXCHANGE_API_KEY', 'DATABASE_URL', 'DATABASE_USER', 'DATABASE_PASSWORD')
env = os.environ.copy()
env_file = root / '.env'
if env_file.exists():
    for line in env_file.read_text(encoding='utf-8').splitlines():
        if '=' not in line or line.lstrip().startswith('#'):
            continue
        name, value = line.split('=', 1)
        name = name.strip()
        if name in names and name not in env:
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in ('"', "'"):
                value = value[1:-1]
            env[name] = value
missing = [name for name in names if name not in env or (name != 'DATABASE_PASSWORD' and not env[name])]
if missing:
    raise SystemExit('필요한 환경변수가 없습니다: ' + ', '.join(missing))
raise SystemExit(subprocess.call([str(root / 'gradlew'), '--quiet', 'collectExchangeRate'], cwd=root, env=env))
