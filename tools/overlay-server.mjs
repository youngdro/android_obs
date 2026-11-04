#!/usr/bin/env node
// 测试网页 + 数据服务器（仅用 Node 内置模块，无需 npm install）。
//   node tools/overlay-server.mjs [端口，默认 8787]
// 手机与电脑在同一局域网时，在 App 中填写：
//   http://<电脑IP>:8787/index.html?ws=ws://<电脑IP>:8787        （需开启“允许局域网 HTTP 明文网页”）
//   http://<电脑IP>:8787/index.html?poll=http://<电脑IP>:8787/data
// 每秒向所有 WebSocket 客户端推送一次 {text, value}；也可以在终端输入一行文字立即推送。
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';

const port = Number(process.argv[2] || 8787);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../app/src/main/assets/testpage');
const types = { '.html': 'text/html; charset=utf-8', '.png': 'image/png', '.svg': 'image/svg+xml', '.js': 'text/javascript', '.css': 'text/css' };
let value = 0;
let text = '来自 overlay-server 的实时数据';
const clients = new Set();

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/data') {
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'access-control-allow-origin': '*', 'cache-control': 'no-store' });
    return res.end(JSON.stringify({ text, value }));
  }
  const file = path.join(root, url.pathname === '/' ? 'index.html' : path.normalize(url.pathname));
  if (!file.startsWith(root) || !fs.existsSync(file)) { res.writeHead(404); return res.end('not found'); }
  res.writeHead(200, { 'content-type': types[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});

// 最小 WebSocket 实现（只发送文本帧）
server.on('upgrade', (req, socket) => {
  const key = req.headers['sec-websocket-key'];
  if (!key) return socket.destroy();
  const accept = crypto.createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
  socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' + accept + '\r\n\r\n');
  clients.add(socket);
  socket.on('close', () => clients.delete(socket));
  socket.on('error', () => clients.delete(socket));
  socket.on('data', buf => { if ((buf[0] & 0x0f) === 0x8) socket.end(); }); // close 帧
});

function send(obj) {
  const payload = Buffer.from(JSON.stringify(obj));
  const len = payload.length;
  const header = len < 126 ? Buffer.from([0x81, len]) : Buffer.from([0x81, 126, len >> 8, len & 0xff]);
  for (const c of clients) c.write(Buffer.concat([header, payload]));
}

setInterval(() => { value++; send({ text, value }); }, 1000);
readline.createInterface({ input: process.stdin }).on('line', line => { text = line; send({ text, value }); });

server.listen(port, '0.0.0.0', () => console.log(`overlay-server: http://0.0.0.0:${port}/index.html  (ws://<IP>:${port})`));
