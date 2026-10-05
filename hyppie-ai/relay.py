#!/usr/bin/env python3
"""Hyppie AI relay: lets the phone reach the laptop's Ollama over Wi-Fi.
Ollama listens on 127.0.0.1:11434 only; this forwards 0.0.0.0:11435 -> 127.0.0.1:11434
and only allows Ollama's chat/generate/tags endpoints, from the local network or your Tailscale devices."""
import ipaddress, socket, threading

LISTEN, TARGET = ('0.0.0.0', 11435), ('127.0.0.1', 11434)
TAILNET = ipaddress.ip_network('100.64.0.0/10')
ALLOWED = (b'POST /api/chat ', b'POST /api/generate ', b'GET /api/tags ', b'GET / ')

def pipe(a, b):
    try:
        while (d := a.recv(65536)):
            b.sendall(d)
    except OSError:
        pass
    finally:
        for s in (a, b):
            try: s.shutdown(socket.SHUT_RDWR)
            except OSError: pass

def handle(c, addr):
    ip = ipaddress.ip_address(addr[0])
    if not (ip.is_private or ip in TAILNET):      # home network or your Tailscale devices only
        return c.close()
    first = c.recv(65536)
    if not first.startswith(ALLOWED):
        c.sendall(b'HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n'); return c.close()
    u = socket.create_connection(TARGET); u.sendall(first)
    threading.Thread(target=pipe, args=(u, c), daemon=True).start()
    pipe(c, u)

s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind(LISTEN); s.listen(16)
print('Hyppie relay on :11435 -> Ollama', flush=True)
while True:
    c, a = s.accept()
    threading.Thread(target=handle, args=(c, a), daemon=True).start()
