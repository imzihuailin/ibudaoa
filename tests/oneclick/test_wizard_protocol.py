"""Exercise the Java wizard protocol against a loopback test peer, with no user data."""
import base64
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading

root = Path(__file__).resolve().parents[2]
jdk = Path(sys.argv[1])
token = '0123456789ABCDEF' * 4
requests = [('BIND', 'AA:BB', 'continue'), ('KEY', '请输入高德 Android Key：', '1234567890abcdef1234567890abcdef'), ('KEY', '复用', '')]
with tempfile.TemporaryDirectory() as temp:
    subprocess.run([str(jdk / 'bin/javac'), '-d', temp, str(root / 'scripts/OneClick.java'), str(root / 'tests/oneclick/WizardProtocolTest.java')], check=True)
    with socket.socket() as server:
        server.bind(('127.0.0.1', 0)); server.listen()
        env = os.environ.copy(); env.update(IBUDAOA_WIZARD_PORT=str(server.getsockname()[1]), IBUDAOA_WIZARD_TOKEN=token)
        proc = subprocess.Popen([str(jdk / 'bin/java'), '-cp', temp, 'WizardProtocolTest'], env=env)
        server.settimeout(10)
        try:
            for action, payload, reply in requests:
                conn, _ = server.accept()
                with conn, conn.makefile('rwb') as stream:
                    parts = stream.readline().decode().rstrip('\n').split('|')
                    assert parts[:2] == [token, action]
                    assert base64.b64decode(parts[2]).decode() == payload
                    stream.write(b'OK|' + base64.b64encode(reply.encode()) + b'\n'); stream.flush()
            assert proc.wait(timeout=10) == 0
        finally:
            if proc.poll() is None: proc.kill()
