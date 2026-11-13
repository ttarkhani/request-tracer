"""Accepts TCP connections on the given port and never answers (simulates a hung aggregator)."""
import socket, sys, time
port = int(sys.argv[1])
s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("127.0.0.1", port)); s.listen(128)
held = []
while True:
    c, _ = s.accept(); held.append(c)
