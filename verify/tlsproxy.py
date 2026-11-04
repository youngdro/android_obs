import socket, ssl, sys, threading
lport, tport, cert, key = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3], sys.argv[4]
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.load_cert_chain(cert, key)
srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); srv.bind(("127.0.0.1", lport)); srv.listen(5)
def pipe(a, b):
    try:
        while True:
            d = a.recv(65536)
            if not d: break
            b.sendall(d)
    except Exception as e: pass
    finally:
        for s in (a, b):
            try: s.close()
            except: pass
while True:
    c, _ = srv.accept()
    try:
        t = ctx.wrap_socket(c, server_side=True)
    except Exception as e:
        print("handshake failed:", e, flush=True); c.close(); continue
    u = socket.create_connection(("127.0.0.1", tport))
    threading.Thread(target=pipe, args=(t, u), daemon=True).start()
    threading.Thread(target=pipe, args=(u, t), daemon=True).start()
